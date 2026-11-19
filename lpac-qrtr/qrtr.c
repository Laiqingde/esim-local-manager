// SPDX-License-Identifier: MIT
// lpac APDU backend: QMI UIM over QRTR (raw AF_QIPCRTR, no glib/libqmi).
// For Qualcomm Android devices with root. Talks directly to the modem UIM
// service to open a logical channel to ISD-R and exchange eUICC APDUs.
#include <driver.h>
#include <euicc/interface.h>
#include <lpac/utils.h>

#include <errno.h>
#include <poll.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/socket.h>
#include <linux/qrtr.h>

#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif

#define QMI_UIM_SEND_APDU            0x003B
#define QMI_UIM_LOGICAL_CHANNEL      0x003F
#define QMI_UIM_OPEN_LOGICAL_CHANNEL 0x0042
#define UIM_SERVICE                  0x0b

static int qfd = -1;
static uint32_t uim_node = 0, uim_port = 0;
static int uim_slot = 1;
static uint8_t last_channel = 0;
static uint16_t txn_ctr = 0;

static int envi(const char *name, int def) {
    const char *v = getenv(name);
    if (!v || !*v) return def;
    return (int)strtol(v, NULL, 0);
}

static int qrtr_discover_uim(void) {
    struct sockaddr_qrtr sa;
    socklen_t sl = sizeof(sa);
    if (getsockname(qfd, (struct sockaddr *)&sa, &sl) < 0) return -1;

    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.server.service = UIM_SERVICE;
    pkt.server.instance = 0;

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = sa.sq_node;
    dst.sq_port = QRTR_PORT_CTRL;

    if (sendto(qfd, &pkt, sizeof(pkt), 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) return -1;

    for (;;) {
        struct pollfd p = {qfd, POLLIN, 0};
        if (poll(&p, 1, 3000) <= 0) break;
        struct qrtr_ctrl_pkt rp;
        ssize_t n = recv(qfd, &rp, sizeof(rp), 0);
        if (n < (ssize_t)sizeof(rp.cmd)) continue;
        if (rp.cmd == QRTR_TYPE_NEW_SERVER) {
            if (rp.server.service == 0 && rp.server.node == 0 && rp.server.port == 0) break; // end
            if (rp.server.service == UIM_SERVICE) {
                uim_node = rp.server.node;
                uim_port = rp.server.port;
                return 0;
            }
        }
    }
    return uim_port ? 0 : -1;
}

// Send a QMI service request and return the TLV region of the response.
static int qmi_txn(uint16_t msg_id, const uint8_t *tlv, uint16_t tlv_len,
                   uint8_t *resp, uint16_t *resp_len) {
    uint8_t buf[2048];
    uint16_t txn = ++txn_ctr;
    if (txn == 0) txn = ++txn_ctr;

    buf[0] = 0x00;                       // control flags: request
    buf[1] = txn & 0xff;                 // txn id LE (2 bytes for service msg)
    buf[2] = (txn >> 8) & 0xff;
    buf[3] = msg_id & 0xff;              // message id LE
    buf[4] = (msg_id >> 8) & 0xff;
    buf[5] = tlv_len & 0xff;             // tlv length LE
    buf[6] = (tlv_len >> 8) & 0xff;
    if (tlv_len) memcpy(buf + 7, tlv, tlv_len);
    uint16_t total = 7 + tlv_len;

    struct sockaddr_qrtr dst;
    memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = uim_node;
    dst.sq_port = uim_port;

    if (sendto(qfd, buf, total, 0, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        fprintf(stderr, "qrtr: sendto failed: %s\n", strerror(errno));
        return -1;
    }

    for (;;) {
        struct pollfd p = {qfd, POLLIN, 0};
        int r = poll(&p, 1, 15000);
        if (r <= 0) {
            fprintf(stderr, "qrtr: response timeout (msg 0x%04x)\n", msg_id);
            return -1;
        }
        uint8_t rb[4096];
        struct sockaddr_qrtr from;
        socklen_t fl = sizeof(from);
        ssize_t n = recvfrom(qfd, rb, sizeof(rb), 0, (struct sockaddr *)&from, &fl);
        if (n < 7) continue;
        if (from.sq_port != uim_port) continue;       // ignore control/other
        uint8_t flags = rb[0];
        uint16_t rtxn = rb[1] | (rb[2] << 8);
        uint16_t rmsg = rb[3] | (rb[4] << 8);
        uint16_t rlen = rb[5] | (rb[6] << 8);
        if (!(flags & 0x02)) continue;                // want response
        if (rmsg != msg_id || rtxn != txn) continue;  // match
        if (7 + rlen > n) rlen = n - 7;
        if (rlen > *resp_len) return -1;
        memcpy(resp, rb + 7, rlen);
        *resp_len = rlen;
        return 0;
    }
}

// find TLV of given type; returns value pointer and sets vlen, or NULL
static const uint8_t *tlv_find(const uint8_t *tlv, uint16_t len, uint8_t type, uint16_t *vlen) {
    uint16_t i = 0;
    while (i + 3 <= len) {
        uint8_t t = tlv[i];
        uint16_t l = tlv[i + 1] | (tlv[i + 2] << 8);
        if (i + 3 + l > len) break;
        if (t == type) {
            *vlen = l;
            return tlv + i + 3;
        }
        i += 3 + l;
    }
    return NULL;
}

// check mandatory result TLV (0x02): 0 = success
static int qmi_result_ok(const uint8_t *tlv, uint16_t len) {
    uint16_t vl = 0;
    const uint8_t *v = tlv_find(tlv, len, 0x02, &vl);
    if (!v || vl < 4) return -1;
    uint16_t result = v[0] | (v[1] << 8);
    uint16_t error = v[2] | (v[3] << 8);
    if (result != 0) {
        fprintf(stderr, "qrtr: QMI result=%u error=%u\n", result, error);
        return -1;
    }
    return 0;
}

static int apdu_connect(struct euicc_ctx *ctx) {
    (void)ctx;
    uim_slot = envi("LPAC_APDU_QRTR_SLOT", envi("LPAC_APDU_QMI_UIM_SLOT", 1));
    qfd = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (qfd < 0) {
        fprintf(stderr, "qrtr: socket(AF_QIPCRTR) failed: %s\n", strerror(errno));
        return -1;
    }
    if (qrtr_discover_uim() != 0) {
        fprintf(stderr, "qrtr: UIM service not found over QRTR\n");
        close(qfd);
        qfd = -1;
        return -1;
    }
    fprintf(stderr, "qrtr: UIM at node=%u port=%u, slot=%d\n", uim_node, uim_port, uim_slot);
    return 0;
}

static void apdu_disconnect(struct euicc_ctx *ctx) {
    (void)ctx;
    if (qfd >= 0) close(qfd);
    qfd = -1;
}

static int apdu_logic_channel_open(struct euicc_ctx *ctx, const uint8_t *aid, uint8_t aid_len) {
    (void)ctx;
    uint8_t tlv[128];
    uint16_t n = 0;
    // Slot 0x01
    tlv[n++] = 0x01; tlv[n++] = 0x01; tlv[n++] = 0x00; tlv[n++] = (uint8_t)uim_slot;
    // AID 0x10 : array u8 size-prefix
    tlv[n++] = 0x10; tlv[n++] = (uint8_t)(1 + aid_len); tlv[n++] = 0x00;
    tlv[n++] = aid_len;
    memcpy(tlv + n, aid, aid_len); n += aid_len;

    uint8_t resp[1024]; uint16_t rl = sizeof(resp);
    if (qmi_txn(QMI_UIM_OPEN_LOGICAL_CHANNEL, tlv, n, resp, &rl)) return -1;
    if (qmi_result_ok(resp, rl)) return -1;
    uint16_t vl = 0;
    const uint8_t *cid = tlv_find(resp, rl, 0x10, &vl);
    if (!cid || vl < 1) {
        fprintf(stderr, "qrtr: open channel: no channel id in response\n");
        return -1;
    }
    last_channel = cid[0];
    return cid[0];
}

static void apdu_logic_channel_close(struct euicc_ctx *ctx, uint8_t channel) {
    (void)ctx;
    uint8_t tlv[16];
    uint16_t n = 0;
    tlv[n++] = 0x01; tlv[n++] = 0x01; tlv[n++] = 0x00; tlv[n++] = (uint8_t)uim_slot;   // slot
    tlv[n++] = 0x11; tlv[n++] = 0x01; tlv[n++] = 0x00; tlv[n++] = channel;             // channel id
    uint8_t resp[256]; uint16_t rl = sizeof(resp);
    qmi_txn(QMI_UIM_LOGICAL_CHANNEL, tlv, n, resp, &rl);
}

static int apdu_transmit(struct euicc_ctx *ctx, uint8_t **rx, uint32_t *rx_len,
                         const uint8_t *tx, uint32_t tx_len) {
    (void)ctx;
    *rx = NULL; *rx_len = 0;
    if (tx_len > 1024) return -1;
    uint8_t tlv[1200];
    uint16_t n = 0;
    // Slot 0x01
    tlv[n++] = 0x01; tlv[n++] = 0x01; tlv[n++] = 0x00; tlv[n++] = (uint8_t)uim_slot;
    // APDU 0x02 : array u16 size-prefix
    tlv[n++] = 0x02;
    tlv[n++] = (uint8_t)((2 + tx_len) & 0xff);
    tlv[n++] = (uint8_t)(((2 + tx_len) >> 8) & 0xff);
    tlv[n++] = tx_len & 0xff;
    tlv[n++] = (tx_len >> 8) & 0xff;
    memcpy(tlv + n, tx, tx_len); n += tx_len;
    // Channel ID 0x10
    tlv[n++] = 0x10; tlv[n++] = 0x01; tlv[n++] = 0x00; tlv[n++] = last_channel;

    uint8_t resp[4096]; uint16_t rl = sizeof(resp);
    if (qmi_txn(QMI_UIM_SEND_APDU, tlv, n, resp, &rl)) return -1;
    if (qmi_result_ok(resp, rl)) return -1;
    uint16_t vl = 0;
    const uint8_t *ar = tlv_find(resp, rl, 0x10, &vl);
    if (!ar || vl < 2) {
        fprintf(stderr, "qrtr: send apdu: no response payload\n");
        return -1;
    }
    uint16_t alen = ar[0] | (ar[1] << 8);
    if (2 + alen > vl) alen = vl - 2;
    *rx = malloc(alen);
    if (!*rx) return -1;
    memcpy(*rx, ar + 2, alen);
    *rx_len = alen;
    return 0;
}

static int libapduinterface_init(struct euicc_apdu_interface *ifs) {
    ifs->connect = apdu_connect;
    ifs->disconnect = apdu_disconnect;
    ifs->logic_channel_open = apdu_logic_channel_open;
    ifs->logic_channel_close = apdu_logic_channel_close;
    ifs->transmit = apdu_transmit;
    return 0;
}

DRIVER_INTERFACE = {
    .type = DRIVER_APDU,
    .name = "qrtr",
    .init = (int (*)(void *))libapduinterface_init,
    .main = NULL,
    .fini = NULL,
};

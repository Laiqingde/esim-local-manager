#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <linux/qrtr.h>
#include <poll.h>

#ifndef AF_QIPCRTR
#define AF_QIPCRTR 42
#endif

int main(void) {
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (fd < 0) { printf("socket(AF_QIPCRTR) 失败: %s\n", strerror(errno)); return 1; }

    struct sockaddr_qrtr sa; socklen_t sl = sizeof(sa);
    if (getsockname(fd, (struct sockaddr*)&sa, &sl) < 0) {
        printf("getsockname 失败: %s\n", strerror(errno)); return 1;
    }
    printf("本地 node=%u port=%u\n", sa.sq_node, sa.sq_port);

    struct qrtr_ctrl_pkt pkt; memset(&pkt, 0, sizeof(pkt));
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.server.service = 0;   // 0 = 所有服务
    pkt.server.instance = 0;

    struct sockaddr_qrtr dst; memset(&dst, 0, sizeof(dst));
    dst.sq_family = AF_QIPCRTR;
    dst.sq_node = sa.sq_node;
    dst.sq_port = QRTR_PORT_CTRL;

    if (sendto(fd, &pkt, sizeof(pkt), 0, (struct sockaddr*)&dst, sizeof(dst)) < 0) {
        printf("sendto(NEW_LOOKUP) 失败: %s\n", strerror(errno)); return 1;
    }
    printf("已发 NEW_LOOKUP，等待服务列表...\n\n");

    int found_uim = 0, n = 0;
    while (1) {
        struct pollfd p = { fd, POLLIN, 0 };
        int r = poll(&p, 1, 2500);
        if (r <= 0) break;
        struct qrtr_ctrl_pkt rp; struct sockaddr_qrtr from; socklen_t fl = sizeof(from);
        ssize_t len = recvfrom(fd, &rp, sizeof(rp), 0, (struct sockaddr*)&from, &fl);
        if (len < (ssize_t)sizeof(rp.cmd)) continue;
        if (rp.cmd == QRTR_TYPE_NEW_SERVER) {
            if (rp.server.service == 0 && rp.server.node == 0 && rp.server.port == 0) {
                printf("\n(服务枚举结束, 共 %d 个)\n", n); break;
            }
            n++;
            const char *name = "";
            switch (rp.server.service) {
                case 0x0b: name = " <== UIM (SIM/eSIM APDU)"; found_uim = 1; break;
                case 0x03: name = " (NAS)"; break;
                case 0x02: name = " (DMS)"; break;
                case 0x01: name = " (WDS)"; break;
                case 0x1a: name = " (WDA)"; break;
            }
            printf("service=0x%02x instance=%u node=%u port=%u%s\n",
                   rp.server.service, rp.server.instance, rp.server.node, rp.server.port, name);
        }
    }
    printf("\n结论: %s\n", found_uim ? "★ 找到 UIM 服务，QMI/QRTR 路可行" : "未发现 UIM 服务");
    close(fd);
    return 0;
}

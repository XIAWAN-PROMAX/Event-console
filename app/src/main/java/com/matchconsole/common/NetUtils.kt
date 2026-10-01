package com.matchconsole.common

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 局域网地址探测。用于接收端展示「IP + 端口 + 二维码」，发送端展示本机 IP。
 */
object NetUtils {

    data class NetIf(val name: String, val ip: String)

    /** 列出所有可用 IPv4 地址，wlan 网卡优先。 */
    fun localIpv4List(): List<NetIf> {
        val result = mutableListOf<NetIf>()
        try {
            val nifs = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in Collections.list(nifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // 169.254.x.x 是链路本地地址，无法用于跨设备直连
                    if (host.startsWith("169.254.")) continue
                    result += NetIf(nif.name, host)
                }
            }
        } catch (t: Throwable) {
            Logx.e("枚举网卡失败", t)
        }
        return result.sortedByDescending { it.name.startsWith("wlan") }
    }

    /** 首选的展示用 IP。 */
    fun primaryIpv4(): String = localIpv4List().firstOrNull()?.ip ?: "0.0.0.0"

    /** 二维码/连接串格式：matchconsole://192.168.1.10:8770 */
    fun buildConnectUri(ip: String, port: Int): String = "matchconsole://$ip:$port"

    /** 解析连接串，返回 ip to port；非法返回 null。 */
    fun parseConnectUri(raw: String): Pair<String, Int>? {
        val text = raw.trim()
        val body = when {
            text.startsWith("matchconsole://") -> text.removePrefix("matchconsole://")
            text.startsWith("http://") -> text.removePrefix("http://")
            text.startsWith("https://") -> text.removePrefix("https://")
            else -> text
        }.substringBefore('/')

        val parts = body.split(':')
        if (parts.size != 2) return null
        val ip = parts[0].trim()
        val port = parts[1].trim().toIntOrNull() ?: return null
        if (ip.isEmpty() || port !in 1..65535) return null
        return ip to port
    }
}
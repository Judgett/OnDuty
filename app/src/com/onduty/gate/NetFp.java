package com.onduty.gate;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络指纹：网关 + DNS + 网段。只依赖 ACCESS_NETWORK_STATE，不需要位置权限、不联网。
 */
public class NetFp {
    /** 当前是否为 Wi-Fi 网络（蜂窝网络不参与地点判定）。 */
    public boolean wifi = false;
    /** 指纹是否有效：至少拿到网关。 */
    public boolean valid = false;
    /** 默认路由网关地址，是判定「同一张网」的主键。 */
    public String gateway = "";
    /** DNS 服务器列表（逗号分隔），用于区分同网关的不同网络。 */
    public String dns = "";
    /** 本机 IPv4 地址与前缀长度，仅作诊断展示，不参与匹配。 */
    public String subnet = "";

    /** 读取当前默认网络的指纹；任何异常都返回无效指纹，绝不误判为在岗。 */
    public static NetFp current(Context c) {
        NetFp f = new NetFp();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return f;
            Network n = cm.getActiveNetwork();
            if (n == null) return f;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return f;
            f.wifi = true;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return f;

            List<RouteInfo> routes = lp.getRoutes();
            if (routes != null) {
                for (int i = 0; i < routes.size(); i++) {
                    RouteInfo r = routes.get(i);
                    if (r == null || !r.isDefaultRoute()) continue;
                    InetAddress gw = r.getGateway();
                    if (gw == null || gw.isAnyLocalAddress()) continue;
                    f.gateway = gw.getHostAddress();
                    break;
                }
            }

            StringBuilder sb = new StringBuilder();
            List<InetAddress> dnss = lp.getDnsServers();
            if (dnss != null) {
                for (int i = 0; i < dnss.size(); i++) {
                    InetAddress a = dnss.get(i);
                    if (a == null || a.isAnyLocalAddress()) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(a.getHostAddress());
                }
            }
            f.dns = sb.toString();

            List<LinkAddress> las = lp.getLinkAddresses();
            if (las != null) {
                for (int i = 0; i < las.size(); i++) {
                    LinkAddress la = las.get(i);
                    if (la == null) continue;
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    f.subnet = a.getHostAddress() + "/" + la.getPrefixLength();
                    break;
                }
            }

            f.valid = f.gateway.length() > 0;
        } catch (Throwable ignored) {
        }
        return f;
    }

    /** 序列化为本地存储的 JSON 字符串。 */
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("gw", gateway);
            o.put("dns", dns);
            o.put("subnet", subnet);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 从本地 JSON 还原指纹；解析失败返回无效指纹。 */
    public static NetFp parse(String json) {
        NetFp f = new NetFp();
        if (json == null || json.length() == 0) return f;
        try {
            JSONObject o = new JSONObject(json);
            f.gateway = o.optString("gw", "");
            f.dns = o.optString("dns", "");
            f.subnet = o.optString("subnet", "");
            f.wifi = true;
            f.valid = f.gateway.length() > 0;
        } catch (Exception ignored) {
        }
        return f;
    }

    /**
     * 判定「是不是同一张网」：网关必须一致，且 DNS 有交集（双方都没 DNS 时只认网关）。
     * 这样既能识别换网，也能避免两个 192.168.1.1 的家庭网被当成同一个。
     */
    public static boolean matches(NetFp a, NetFp b) {
        if (a == null || b == null) return false;
        if (!a.valid || !b.valid) return false;
        if (a.gateway.length() == 0 || !a.gateway.equals(b.gateway)) return false;
        if (a.dns.length() == 0 || b.dns.length() == 0) return true;
        String[] x = a.dns.split(",");
        String[] y = b.dns.split(",");
        for (int i = 0; i < x.length; i++) {
            for (int j = 0; j < y.length; j++) {
                if (x[i].trim().equals(y[j].trim())) return true;
            }
        }
        return false;
    }

    /** 给状态胶囊用的短标签。 */
    public String shortLabel() {
        if (!wifi) return "未连接 Wi-Fi";
        if (gateway.length() == 0) return "已连 Wi-Fi";
        return gateway;
    }

    /** 给诊断区用的完整指纹描述。 */
    public String detail() {
        List<String> parts = new ArrayList<String>();
        if (gateway.length() > 0) parts.add("网关 " + gateway);
        if (dns.length() > 0) parts.add("DNS " + dns.replace(",", " / "));
        if (subnet.length() > 0) parts.add(subnet);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** 把已保存指纹格式化为一行摘要（网关 · 首个 DNS）。 */
    public static String describeSaved(String json) {
        NetFp f = parse(json);
        if (!f.valid) return "";
        return f.gateway + (f.dns.length() > 0 ? " · DNS " + f.dns.split(",")[0] : "");
    }
}

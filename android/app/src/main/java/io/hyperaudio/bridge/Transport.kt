package io.hyperaudio.bridge

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*
import org.json.JSONObject

object Transport {
    @SuppressLint("CustomX509TrustManager") // Self-signed peers are trusted only via the exact SHA-256 fingerprint scanned from the PC.
    fun connect(host: String, port: Int, pin: String, timeout: Int = 5000): SSLSocket {
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "证书指纹无效" }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) = throw java.security.cert.CertificateException("不接受客户端证书")
            override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                if (chain.isEmpty()) throw java.security.cert.CertificateException("缺少证书")
                val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(actual, expected)) throw java.security.cert.CertificateException("电脑身份发生变化，请重新配对")
                chain[0].checkValidity()
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val raw = Socket()
        try {
            raw.keepAlive = true
            raw.connect(InetSocketAddress(host, port), timeout)
            raw.tcpNoDelay = true
            val socket = tls.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.soTimeout = 7000
            socket.startHandshake()
            return socket
        } catch (e: Exception) { raw.close(); throw e }
    }
    fun request(socket: SSLSocket, json: JSONObject): JSONObject {
        socket.outputStream.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
        return readMessage(socket.inputStream).also { check(it.optBoolean("ok")) { it.optString("error", "电脑拒绝连接") } }
    }
    fun readMessage(input: InputStream): JSONObject {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val b = input.read()
            if (b == -1) throw EOFException("电脑关闭了连接，请检查配对或接收端状态")
            if (b == 10) return JSONObject(bytes.toString("UTF-8"))
            bytes.write(b)
        }
        throw IOException("响应过长")
    }
    fun pair(text: String): Computer {
        val uri = Uri.parse(text.trim())
        require(uri.scheme == "hyperaudio" && uri.host == "pair") { "请扫描或粘贴电脑显示的配对地址" }
        fun get(key: String) = requireNotNull(uri.getQueryParameter(key)) { "配对地址缺少 $key" }
        val host = get("host").also { require(it.isNotBlank() && it.length <= 253) }
        val port = get("port").toInt().also { require(it in 1..65535) }
        val pin = get("pin")
        val id = get("id").also { require(it.matches(Regex("[0-9a-f]{32}"))) }
        val secret = get("secret").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val name = get("name").take(80)
        val hosts = ((uri.getQueryParameter("hosts") ?: "").split(',') + host).filter { it.isNotBlank() && it.length <= 253 }.distinct().take(16)
        var connected: SSLSocket? = null
        var failure: Exception? = null
        for (candidate in hosts) {
            try { connected = connect(candidate, port, pin, 1500); break }
            catch (e: Exception) { failure = e }
        }
        return (connected ?: throw IOException("未能连接电脑，请确认两端网络互通且电脑防火墙允许连接", failure)).use { socket ->
            val response = request(socket, JSONObject().put("version", 1).put("op", "pair").put("deviceId", BridgeApp.store.deviceId).put("name", Build.MODEL.take(80)).put("secret", secret))
            val token = response.getString("token").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            Computer(id, name, socket.inetAddress.hostAddress ?: host, port, pin, token).also { BridgeApp.store.save(it) }
        }
    }
}

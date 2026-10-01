package com.matchconsole.signaling

import org.json.JSONObject

/**
 * 局域网信令协议（自实现）。
 *
 * 传输层：TCP，UTF-8，**一条 JSON 一行**（\n 分隔）。
 * 角色：接收端 = Server，发送端 = Client。
 *
 * 交互时序：
 *   Client -> hello
 *   Client -> offer   (SDP)
 *   Server -> answer  (SDP)
 *   双方   <-> ice    (双向，多条)
 *   任一方 -> bye
 *
 * 媒体不经过本通道，协商完成后由 WebRTC P2P 直连传输。
 */
data class SignalingMessage(
    val type: String,
    val sdp: String? = null,
    val sdpType: String? = null,
    val mid: String? = null,
    val mLineIndex: Int = 0,
    val candidate: String? = null,
    val role: String? = null,
    val note: String? = null
) {

    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        sdp?.let { json.put("sdp", it) }
        sdpType?.let { json.put("sdpType", it) }
        mid?.let { json.put("mid", it) }
        if (type == TYPE_ICE) json.put("mLineIndex", mLineIndex)
        candidate?.let { json.put("candidate", it) }
        role?.let { json.put("role", it) }
        note?.let { json.put("note", it) }
        return json.toString()
    }

    companion object {
        const val TYPE_HELLO = "hello"
        const val TYPE_OFFER = "offer"
        const val TYPE_ANSWER = "answer"
        const val TYPE_ICE = "ice"
        const val TYPE_BYE = "bye"

        const val ROLE_SENDER = "sender"
        const val ROLE_RECEIVER = "receiver"

        fun hello(role: String, note: String? = null) =
            SignalingMessage(type = TYPE_HELLO, role = role, note = note)

        fun offer(sdp: String, sdpType: String) =
            SignalingMessage(type = TYPE_OFFER, sdp = sdp, sdpType = sdpType)

        fun answer(sdp: String, sdpType: String) =
            SignalingMessage(type = TYPE_ANSWER, sdp = sdp, sdpType = sdpType)

        fun ice(mid: String?, mLineIndex: Int, candidate: String) =
            SignalingMessage(type = TYPE_ICE, mid = mid, mLineIndex = mLineIndex, candidate = candidate)

        fun bye(note: String? = null) = SignalingMessage(type = TYPE_BYE, note = note)

        fun parse(line: String): SignalingMessage? {
            val text = line.trim()
            if (text.isEmpty()) return null
            return try {
                val json = JSONObject(text)
                val type = json.optString("type").ifEmpty { return null }
                SignalingMessage(
                    type = type,
                    sdp = json.optStringOrNull("sdp"),
                    sdpType = json.optStringOrNull("sdpType"),
                    mid = json.optStringOrNull("mid"),
                    mLineIndex = json.optInt("mLineIndex", 0),
                    candidate = json.optStringOrNull("candidate"),
                    role = json.optStringOrNull("role"),
                    note = json.optStringOrNull("note")
                )
            } catch (t: Throwable) {
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            val v = optString(key)
            return v.ifEmpty { null }
        }
    }
}

/** 信令链路状态。 */
enum class SignalingState {
    IDLE,
    LISTENING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}
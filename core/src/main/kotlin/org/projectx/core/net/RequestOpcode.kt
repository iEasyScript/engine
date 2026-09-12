package org.projectx.core.net

object RequestOpcode {
    const val JS5_FILE_PREFETCH = 0
    const val JS5_FILE_URGENT = 1
    const val JS5_NXT_FILE_PREFETCH = 32
    const val JS5_NXT_FILE_URGENT_1 = 17
    const val JS5_NXT_FILE_URGENT_2 = 33

    const val STATUS_LOGGED_IN = 2
    const val STATUS_LOGGED_OUT = 3
    const val XOR_KEY_UPDATE = 4
    const val ACKNOWLEDGE = 6
    const val DISCONNECT = 7

    const val SESSION = 10
    const val CONNECT_LOGIN = 14
    const val JS5_INIT = 15
    const val LOGIN = 16
    const val RECONNECT = 18
    const val LOBBY = 19
    const val ACCOUNT_CREATION = 28

    /**
     * Returns true if the opcode is any JS5 file request.
     * NXT client builds opcodes as: typeByte | (priorityValue << 4)
     *   typeByte: 0 = prefetch, 1 = urgent
     *   priorityValue: 0-7 (priority level)
     * So valid file request opcodes have bits 1-3 clear: (opcode & 0x0E) == 0
     * This covers: 0x00, 0x01, 0x10, 0x11, 0x20, 0x21, 0x30, 0x31, etc.
     */
    fun isFileRequest(opcode: Int): Boolean = (opcode and 0x0E) == 0

    /** Returns true if the file request is urgent (bit 0 set). */
    fun isUrgent(opcode: Int): Boolean = (opcode and 1) != 0
}

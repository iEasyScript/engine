package org.projectx.packetlog.upload

import kotlinx.serialization.Serializable

/**
 * What a client declares about a session before sending any of it.
 *
 * ⛔ Lives here, in the shared module, because it is a contract between two programs. Written out
 * by hand on one side and declared as a class on the other, a renamed field does not fail: the
 * sender keeps sending the old name and the receiver quietly substitutes a default. One definition
 * makes that a compile error instead.
 *
 * Every field is needed to interpret the bytes later - the same packet means something different
 * under a different client build or prot table, so a capture without this is unreadable.
 */
@Serializable
class SessionManifest(
    val uuid: String,
    val kind: String,
    val revision: Int,
    val protTableHash: String,
    val clientBuild: String,
    val engineBuild: String,
    val platform: String,
    val arch: String,
    val startedEpochMs: Long,
    val consentVersion: Int,
    val redactionPolicy: String,
)

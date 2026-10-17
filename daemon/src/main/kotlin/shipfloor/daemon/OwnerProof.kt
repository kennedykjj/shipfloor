package shipfloor.daemon

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom

/** Daemon-generated proof that a request really comes from the owner. */
class OwnerProof private constructor(private val tokenBytes: ByteArray) {

    fun matches(candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(tokenBytes, candidate.toByteArray(Charsets.UTF_8))
    }

    override fun toString(): String = "OwnerProof"

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        private const val TOKEN_BYTES = 32
        private const val DIR_MODE = "rwx------"
        private const val FILE_MODE = "rw-------"

        fun create(path: Path, random: SecureRandom): OwnerProof {
            val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            val absolute = path.toAbsolutePath()
            val parent = requireNotNull(absolute.parent) { "owner token path has no parent directory: $absolute" }
            try {
                if (!Files.exists(parent)) {
                    Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(DIR_MODE)))
                }
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString(DIR_MODE))
                Files.deleteIfExists(absolute)
                val created = try {
                    Files.createFile(absolute, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(FILE_MODE)))
                } catch (e: FileAlreadyExistsException) {
                    throw IllegalStateException("owner token file appeared during creation: $absolute", e)
                }
                Files.setPosixFilePermissions(created, PosixFilePermissions.fromString(FILE_MODE))
                Files.writeString(created, token)
                check(!Files.isSymbolicLink(created) && Files.isRegularFile(created, LinkOption.NOFOLLOW_LINKS)) {
                    "owner token path is not a regular file: $absolute"
                }
            } catch (e: UnsupportedOperationException) {
                throw IllegalStateException("cannot set owner token permissions: $absolute", e)
            }
            return OwnerProof(token.toByteArray(Charsets.UTF_8))
        }
    }
}

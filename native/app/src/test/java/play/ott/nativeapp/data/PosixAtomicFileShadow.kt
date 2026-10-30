package play.ott.nativeapp.data

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Android's rename replaces the destination atomically. Host File.renameTo can
 * fail to replace an existing file on Windows. Shadow only that OS primitive;
 * keep the real AtomicFile, AES vault, SQLite and repository logic in this test.
 * Unexpected I/O errors propagate: never accept a missing or partial write.
 */
@Implements(AtomicFile::class)
class PosixAtomicFileShadow {
    companion object {
        @JvmStatic
        @Implementation(minSdk = 30)
        fun rename(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

import java.util.zip.Deflater
import java.io.ByteArrayOutputStream
import java.util.Base64

fun compressAndEncode(jsonString: String): String {
    // 1. Get bytes from the string
    val inputBytes = jsonString.toByteArray(Charsets.UTF_8)

    // 2. Compress the bytes using zlib (DEFLATE)
    val deflater = Deflater()
    deflater.setInput(inputBytes)
    deflater.finish()

    val outputStream = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (!deflater.finished()) {
        val count = deflater.deflate(buffer)
        outputStream.write(buffer, 0, count)
    }
    outputStream.close()
    val compressedBytes = outputStream.toByteArray()

    // 3. Encode the *compressed* bytes to Base64
    val base64String = Base64.getEncoder().encodeToString(compressedBytes)

    // 4. Add the version '0'
    return "0" + base64String
}

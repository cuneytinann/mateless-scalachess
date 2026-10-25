package sbt {
  object IO { def write(f: java.io.File, s: String): Unit = { f.getParentFile.mkdirs(); java.nio.file.Files.writeString(f.toPath, s) } }
}
package object sbt {
  type File = java.io.File
  implicit class RichFile(private val f: java.io.File) extends AnyVal { def /(s: String): java.io.File = new java.io.File(f, s) }
}

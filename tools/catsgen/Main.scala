object catsgen { def main(a: Array[String]): Unit = { KernelBoiler.gen(new java.io.File(a(0))).foreach(println); Boilerplate.gen(new java.io.File(a(1))).foreach(println) } }

# Stohn Wallet release shrink rules.
# The app is reflection-light; default Android rules cover Compose and its dependencies.
# commons-compress / xz / zxing are used directly from Kotlin, so no reflection keeps needed.
# Keep crash-stack line numbers readable for release diagnostics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

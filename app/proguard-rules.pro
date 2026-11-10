# Stohn Wallet release shrink rules.
# The app is reflection-light; default Android rules cover Compose and its dependencies.
# commons-compress / xz / zxing are used directly from Kotlin, so no reflection keeps needed.
# Keep crash-stack line numbers readable for release diagnostics.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Tink (pulled in by androidx.security) annotates its API with errorprone and jsr305 types that are
# never present at runtime. R8 fails a release build when a referenced class is missing even though
# it is only an annotation, so they are declared absent rather than added as dependencies that would
# ship nothing.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

# 精简依赖：Xorg dummy + x11vnc + ffmpeg + Java 17 (full JRE, 非 headless — MC 1.8.8 LWJGL 依赖 libjawt.so)
#   + Mesa llvmpipe: libgl1-mesa-glx libgl1-mesa-dri 提供 llvmpipe 软件 GL
#   + xserver-xorg-video-dummy + xserver-xorg-core + xinit: Xorg dummy 驱动 + GLX
#   + x11-xserver-utils: 提供 xrandr 作为 fallback（我们用 fake xrandr 绕过 LWJGL NPE）
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        x11vnc \
        ffmpeg \
        xdotool \
        openjdk-17-jre \
        xserver-xorg-video-dummy \
        xserver-xorg-core \
        xinit \
        ca-certificates \
        wget \
        unzip \
        curl \
        procps \
        python3 \
        python3-pip \
        x11-utils \
        x11-xserver-utils \
        libgl1-mesa-glx \
        libgl1-mesa-dri \
        libegl1-mesa \
        libgles2-mesa \
        libglx-mesa0 \
        mesa-utils \
        libxext6 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        libxrandr2 \
        libxcursor1 \
        libxinerama1 \
        libxxf86vm1 \
        libasound2 \
    && rm -rf /var/lib/apt/lists/* \
    && pip3 install --break-system-packages --no-cache-dir flask

WORKDIR /mc

# 从 Maven Central (org.jmonkeyengine 托管) 获取 LWJGL 2.9.4 jar + Linux natives
RUN set -eux; \
    mkdir -p /mc/lib/natives; \
    MVN="https://repo1.maven.org/maven2"; \
    wget -q --tries=5 --timeout=60 -O /mc/lib/lwjgl.jar \
        "$MVN/org/jmonkeyengine/lwjgl/2.9.4/lwjgl-2.9.4.jar"; \
    wget -q --tries=5 --timeout=60 -O /mc/lib/lwjgl_util.jar \
        "$MVN/org/jmonkeyengine/lwjgl-util/2.9.4/lwjgl-util-2.9.4.jar" || true; \
    wget -q --tries=5 --timeout=60 -O /tmp/lwjgl-natives-linux.jar \
        "$MVN/org/jmonkeyengine/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-linux.jar" && \
        unzip -q -o -d /mc/lib/natives /tmp/lwjgl-natives-linux.jar && \
        rm /tmp/lwjgl-natives-linux.jar || \
    ( \
      echo "[warn] jmonkeyengine natives-linux jar not found, trying fallback..."; \
      wget -q --tries=5 --timeout=60 -O /tmp/jinput-natives.jar \
        "$MVN/net/java/jinput/jinput-platform/2.0.5/jinput-platform-2.0.5-natives-linux.jar" && \
      unzip -q -o -d /mc/lib/natives /tmp/jinput-natives.jar && \
      rm /tmp/jinput-natives.jar || true \
    ); \
    wget -q --tries=5 --timeout=60 -O /mc/lib/jinput.jar \
        "$MVN/net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar" || true; \
    wget -q --tries=5 --timeout=60 -O /mc/lib/jutils.jar \
        "$MVN/net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar" || true; \
    wget -q --tries=5 --timeout=60 -O /mc/lib/vecmath.jar \
        "$MVN/javax/vecmath/vecmath/1.5.2/vecmath-1.5.2.jar" || true; \
    ls -la /mc/lib/ /mc/lib/natives/ 2>&1

# Minecraft 1.8.8 运行时 libraries — BMCLAPI 优先 Mojang fallback，SHA1 变量直比（绕过 sha1sum -c checksum-file / pipe 竞态）
RUN set -eux; \
    cd /mc/lib; \
    _expect=; _actual=; \
    wget -q --tries=3 --timeout=60 -O "netty-1.8.8.jar" "https://bmclapi2.bangbang93.com/maven/com/mojang/netty/1.8.8/netty-1.8.8.jar" || wget -q --tries=2 --timeout=45 -O "netty-1.8.8.jar" "https://libraries.minecraft.net/com/mojang/netty/1.8.8/netty-1.8.8.jar"; \
    _expect="0a796914d1c8a55b4da9f4a8856dd9623375d8bb"; \
    _actual=$(sha1sum "netty-1.8.8.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL netty-1.8.8.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "netty-1.8.8.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "oshi-core-1.1.jar" "https://bmclapi2.bangbang93.com/maven/oshi-project/oshi-core/1.1/oshi-core-1.1.jar" || wget -q --tries=2 --timeout=45 -O "oshi-core-1.1.jar" "https://libraries.minecraft.net/oshi-project/oshi-core/1.1/oshi-core-1.1.jar"; \
    _expect="9ddf7b048a8d701be231c0f4f95fd986198fd2d8"; \
    _actual=$(sha1sum "oshi-core-1.1.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL oshi-core-1.1.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "oshi-core-1.1.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "jna-3.4.0.jar" "https://bmclapi2.bangbang93.com/maven/net/java/dev/jna/jna/3.4.0/jna-3.4.0.jar" || wget -q --tries=2 --timeout=45 -O "jna-3.4.0.jar" "https://libraries.minecraft.net/net/java/dev/jna/jna/3.4.0/jna-3.4.0.jar"; \
    _expect="803ff252fedbd395baffd43b37341dc4a150a554"; \
    _actual=$(sha1sum "jna-3.4.0.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL jna-3.4.0.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "jna-3.4.0.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "platform-3.4.0.jar" "https://bmclapi2.bangbang93.com/maven/net/java/dev/jna/platform/3.4.0/platform-3.4.0.jar" || wget -q --tries=2 --timeout=45 -O "platform-3.4.0.jar" "https://libraries.minecraft.net/net/java/dev/jna/platform/3.4.0/platform-3.4.0.jar"; \
    _expect="e3f70017be8100d3d6923f50b3d2ee17714e9c13"; \
    _actual=$(sha1sum "platform-3.4.0.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL platform-3.4.0.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "platform-3.4.0.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "icu4j-core-mojang-51.2.jar" "https://bmclapi2.bangbang93.com/maven/com/ibm/icu/icu4j-core-mojang/51.2/icu4j-core-mojang-51.2.jar" || wget -q --tries=2 --timeout=45 -O "icu4j-core-mojang-51.2.jar" "https://libraries.minecraft.net/com/ibm/icu/icu4j-core-mojang/51.2/icu4j-core-mojang-51.2.jar"; \
    _expect="63d216a9311cca6be337c1e458e587f99d382b84"; \
    _actual=$(sha1sum "icu4j-core-mojang-51.2.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL icu4j-core-mojang-51.2.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "icu4j-core-mojang-51.2.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "jopt-simple-4.6.jar" "https://bmclapi2.bangbang93.com/maven/net/sf/jopt-simple/jopt-simple/4.6/jopt-simple-4.6.jar" || wget -q --tries=2 --timeout=45 -O "jopt-simple-4.6.jar" "https://libraries.minecraft.net/net/sf/jopt-simple/jopt-simple/4.6/jopt-simple-4.6.jar"; \
    _expect="306816fb57cf94f108a43c95731b08934dcae15c"; \
    _actual=$(sha1sum "jopt-simple-4.6.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL jopt-simple-4.6.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "jopt-simple-4.6.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "codecjorbis-20101023.jar" "https://bmclapi2.bangbang93.com/maven/com/paulscode/codecjorbis/20101023/codecjorbis-20101023.jar" || wget -q --tries=2 --timeout=45 -O "codecjorbis-20101023.jar" "https://libraries.minecraft.net/com/paulscode/codecjorbis/20101023/codecjorbis-20101023.jar"; \
    _expect="c73b5636faf089d9f00e8732a829577de25237ee"; \
    _actual=$(sha1sum "codecjorbis-20101023.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL codecjorbis-20101023.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "codecjorbis-20101023.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "codecwav-20101023.jar" "https://bmclapi2.bangbang93.com/maven/com/paulscode/codecwav/20101023/codecwav-20101023.jar" || wget -q --tries=2 --timeout=45 -O "codecwav-20101023.jar" "https://libraries.minecraft.net/com/paulscode/codecwav/20101023/codecwav-20101023.jar"; \
    _expect="12f031cfe88fef5c1dd36c563c0a3a69bd7261da"; \
    _actual=$(sha1sum "codecwav-20101023.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL codecwav-20101023.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "codecwav-20101023.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "libraryjavasound-20101123.jar" "https://bmclapi2.bangbang93.com/maven/com/paulscode/libraryjavasound/20101123/libraryjavasound-20101123.jar" || wget -q --tries=2 --timeout=45 -O "libraryjavasound-20101123.jar" "https://libraries.minecraft.net/com/paulscode/libraryjavasound/20101123/libraryjavasound-20101123.jar"; \
    _expect="5c5e304366f75f9eaa2e8cca546a1fb6109348b3"; \
    _actual=$(sha1sum "libraryjavasound-20101123.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL libraryjavasound-20101123.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "libraryjavasound-20101123.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "librarylwjglopenal-20100824.jar" "https://bmclapi2.bangbang93.com/maven/com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar" || wget -q --tries=2 --timeout=45 -O "librarylwjglopenal-20100824.jar" "https://libraries.minecraft.net/com/paulscode/librarylwjglopenal/20100824/librarylwjglopenal-20100824.jar"; \
    _expect="73e80d0794c39665aec3f62eee88ca91676674ef"; \
    _actual=$(sha1sum "librarylwjglopenal-20100824.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL librarylwjglopenal-20100824.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "librarylwjglopenal-20100824.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "soundsystem-20120107.jar" "https://bmclapi2.bangbang93.com/maven/com/paulscode/soundsystem/20120107/soundsystem-20120107.jar" || wget -q --tries=2 --timeout=45 -O "soundsystem-20120107.jar" "https://libraries.minecraft.net/com/paulscode/soundsystem/20120107/soundsystem-20120107.jar"; \
    _expect="419c05fe9be71f792b2d76cfc9b67f1ed0fec7f6"; \
    _actual=$(sha1sum "soundsystem-20120107.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL soundsystem-20120107.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "soundsystem-20120107.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "netty-all-4.0.23.Final.jar" "https://bmclapi2.bangbang93.com/maven/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar" || wget -q --tries=2 --timeout=45 -O "netty-all-4.0.23.Final.jar" "https://libraries.minecraft.net/io/netty/netty-all/4.0.23.Final/netty-all-4.0.23.Final.jar"; \
    _expect="0294104aaf1781d6a56a07d561e792c5d0c95f45"; \
    _actual=$(sha1sum "netty-all-4.0.23.Final.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL netty-all-4.0.23.Final.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "netty-all-4.0.23.Final.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "guava-17.0.jar" "https://bmclapi2.bangbang93.com/maven/com/google/guava/guava/17.0/guava-17.0.jar" || wget -q --tries=2 --timeout=45 -O "guava-17.0.jar" "https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar"; \
    _expect="9c6ef172e8de35fd8d4d8783e4821e57cdef7445"; \
    _actual=$(sha1sum "guava-17.0.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL guava-17.0.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "guava-17.0.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "commons-lang3-3.3.2.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/commons/commons-lang3/3.3.2/commons-lang3-3.3.2.jar" || wget -q --tries=2 --timeout=45 -O "commons-lang3-3.3.2.jar" "https://libraries.minecraft.net/org/apache/commons/commons-lang3/3.3.2/commons-lang3-3.3.2.jar"; \
    _expect="90a3822c38ec8c996e84c16a3477ef632cbc87a3"; \
    _actual=$(sha1sum "commons-lang3-3.3.2.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL commons-lang3-3.3.2.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "commons-lang3-3.3.2.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "commons-io-2.4.jar" "https://bmclapi2.bangbang93.com/maven/commons-io/commons-io/2.4/commons-io-2.4.jar" || wget -q --tries=2 --timeout=45 -O "commons-io-2.4.jar" "https://libraries.minecraft.net/commons-io/commons-io/2.4/commons-io-2.4.jar"; \
    _expect="b1b6ea3b7e4aa4f492509a4952029cd8e48019ad"; \
    _actual=$(sha1sum "commons-io-2.4.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL commons-io-2.4.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "commons-io-2.4.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "commons-codec-1.9.jar" "https://bmclapi2.bangbang93.com/maven/commons-codec/commons-codec/1.9/commons-codec-1.9.jar" || wget -q --tries=2 --timeout=45 -O "commons-codec-1.9.jar" "https://libraries.minecraft.net/commons-codec/commons-codec/1.9/commons-codec-1.9.jar"; \
    _expect="9ce04e34240f674bc72680f8b843b1457383161a"; \
    _actual=$(sha1sum "commons-codec-1.9.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL commons-codec-1.9.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "commons-codec-1.9.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "jinput-2.0.5.jar" "https://bmclapi2.bangbang93.com/maven/net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar" || wget -q --tries=2 --timeout=45 -O "jinput-2.0.5.jar" "https://libraries.minecraft.net/net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar"; \
    _expect="39c7796b469a600f72380316f6b1f11db6c2c7c4"; \
    _actual=$(sha1sum "jinput-2.0.5.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL jinput-2.0.5.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "jinput-2.0.5.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "jutils-1.0.0.jar" "https://bmclapi2.bangbang93.com/maven/net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar" || wget -q --tries=2 --timeout=45 -O "jutils-1.0.0.jar" "https://libraries.minecraft.net/net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar"; \
    _expect="e12fe1fda814bd348c1579329c86943d2cd3c6a6"; \
    _actual=$(sha1sum "jutils-1.0.0.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL jutils-1.0.0.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "jutils-1.0.0.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "gson-2.2.4.jar" "https://bmclapi2.bangbang93.com/maven/com/google/code/gson/gson/2.2.4/gson-2.2.4.jar" || wget -q --tries=2 --timeout=45 -O "gson-2.2.4.jar" "https://libraries.minecraft.net/com/google/code/gson/gson/2.2.4/gson-2.2.4.jar"; \
    _expect="a60a5e993c98c864010053cb901b7eab25306568"; \
    _actual=$(sha1sum "gson-2.2.4.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL gson-2.2.4.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "gson-2.2.4.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "authlib-1.5.21.jar" "https://bmclapi2.bangbang93.com/maven/com/mojang/authlib/1.5.21/authlib-1.5.21.jar" || wget -q --tries=2 --timeout=45 -O "authlib-1.5.21.jar" "https://libraries.minecraft.net/com/mojang/authlib/1.5.21/authlib-1.5.21.jar"; \
    _expect="aefba0d5b53fbcb70860bc8046ab95d5854c07a5"; \
    _actual=$(sha1sum "authlib-1.5.21.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL authlib-1.5.21.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "authlib-1.5.21.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "realms-1.7.39.jar" "https://bmclapi2.bangbang93.com/maven/com/mojang/realms/1.7.39/realms-1.7.39.jar" || wget -q --tries=2 --timeout=45 -O "realms-1.7.39.jar" "https://libraries.minecraft.net/com/mojang/realms/1.7.39/realms-1.7.39.jar"; \
    _expect="c282954ce2a3bc62812e0d41c05f179b3b5839d7"; \
    _actual=$(sha1sum "realms-1.7.39.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL realms-1.7.39.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "realms-1.7.39.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "commons-compress-1.8.1.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar" || wget -q --tries=2 --timeout=45 -O "commons-compress-1.8.1.jar" "https://libraries.minecraft.net/org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar"; \
    _expect="a698750c16740fd5b3871425f4cb3bbaa87f529d"; \
    _actual=$(sha1sum "commons-compress-1.8.1.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL commons-compress-1.8.1.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "commons-compress-1.8.1.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "httpclient-4.3.3.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/httpcomponents/httpclient/4.3.3/httpclient-4.3.3.jar" || wget -q --tries=2 --timeout=45 -O "httpclient-4.3.3.jar" "https://libraries.minecraft.net/org/apache/httpcomponents/httpclient/4.3.3/httpclient-4.3.3.jar"; \
    _expect="18f4247ff4572a074444572cee34647c43e7c9c7"; \
    _actual=$(sha1sum "httpclient-4.3.3.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL httpclient-4.3.3.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "httpclient-4.3.3.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "commons-logging-1.1.3.jar" "https://bmclapi2.bangbang93.com/maven/commons-logging/commons-logging/1.1.3/commons-logging-1.1.3.jar" || wget -q --tries=2 --timeout=45 -O "commons-logging-1.1.3.jar" "https://libraries.minecraft.net/commons-logging/commons-logging/1.1.3/commons-logging-1.1.3.jar"; \
    _expect="f6f66e966c70a83ffbdb6f17a0919eaf7c8aca7f"; \
    _actual=$(sha1sum "commons-logging-1.1.3.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL commons-logging-1.1.3.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "commons-logging-1.1.3.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "httpcore-4.3.2.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/httpcomponents/httpcore/4.3.2/httpcore-4.3.2.jar" || wget -q --tries=2 --timeout=45 -O "httpcore-4.3.2.jar" "https://libraries.minecraft.net/org/apache/httpcomponents/httpcore/4.3.2/httpcore-4.3.2.jar"; \
    _expect="31fbbff1ddbf98f3aa7377c94d33b0447c646b6e"; \
    _actual=$(sha1sum "httpcore-4.3.2.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL httpcore-4.3.2.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "httpcore-4.3.2.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "log4j-api-2.0-beta9.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar" || wget -q --tries=2 --timeout=45 -O "log4j-api-2.0-beta9.jar" "https://libraries.minecraft.net/org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar"; \
    _expect="1dd66e68cccd907880229f9e2de1314bd13ff785"; \
    _actual=$(sha1sum "log4j-api-2.0-beta9.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL log4j-api-2.0-beta9.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "log4j-api-2.0-beta9.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "log4j-core-2.0-beta9.jar" "https://bmclapi2.bangbang93.com/maven/org/apache/logging/log4j/log4j-core/2.0-beta9/log4j-core-2.0-beta9.jar" || wget -q --tries=2 --timeout=45 -O "log4j-core-2.0-beta9.jar" "https://libraries.minecraft.net/org/apache/logging/log4j/log4j-core/2.0-beta9/log4j-core-2.0-beta9.jar"; \
    _expect="678861ba1b2e1fccb594bb0ca03114bb05da9695"; \
    _actual=$(sha1sum "log4j-core-2.0-beta9.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL log4j-core-2.0-beta9.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "log4j-core-2.0-beta9.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "lwjgl-2.9.4-nightly-20150209.jar" "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar" || wget -q --tries=2 --timeout=45 -O "lwjgl-2.9.4-nightly-20150209.jar" "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar"; \
    _expect="697517568c68e78ae0b4544145af031c81082dfe"; \
    _actual=$(sha1sum "lwjgl-2.9.4-nightly-20150209.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL lwjgl-2.9.4-nightly-20150209.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "lwjgl-2.9.4-nightly-20150209.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "lwjgl_util-2.9.4-nightly-20150209.jar" "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar" || wget -q --tries=2 --timeout=45 -O "lwjgl_util-2.9.4-nightly-20150209.jar" "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl_util/2.9.4-nightly-20150209/lwjgl_util-2.9.4-nightly-20150209.jar"; \
    _expect="d51a7c040a721d13efdfbd34f8b257b2df882ad0"; \
    _actual=$(sha1sum "lwjgl_util-2.9.4-nightly-20150209.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL lwjgl_util-2.9.4-nightly-20150209.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "lwjgl_util-2.9.4-nightly-20150209.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "lwjgl-2.9.2-nightly-20140822.jar" "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/lwjgl/2.9.2-nightly-20140822/lwjgl-2.9.2-nightly-20140822.jar" || wget -q --tries=2 --timeout=45 -O "lwjgl-2.9.2-nightly-20140822.jar" "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.2-nightly-20140822/lwjgl-2.9.2-nightly-20140822.jar"; \
    _expect="7707204c9ffa5d91662de95f0a224e2f721b22af"; \
    _actual=$(sha1sum "lwjgl-2.9.2-nightly-20140822.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL lwjgl-2.9.2-nightly-20140822.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "lwjgl-2.9.2-nightly-20140822.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "lwjgl_util-2.9.2-nightly-20140822.jar" "https://bmclapi2.bangbang93.com/maven/org/lwjgl/lwjgl/lwjgl_util/2.9.2-nightly-20140822/lwjgl_util-2.9.2-nightly-20140822.jar" || wget -q --tries=2 --timeout=45 -O "lwjgl_util-2.9.2-nightly-20140822.jar" "https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl_util/2.9.2-nightly-20140822/lwjgl_util-2.9.2-nightly-20140822.jar"; \
    _expect="f0e612c840a7639c1f77f68d72a28dae2f0c8490"; \
    _actual=$(sha1sum "lwjgl_util-2.9.2-nightly-20140822.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL lwjgl_util-2.9.2-nightly-20140822.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "lwjgl_util-2.9.2-nightly-20140822.jar: OK, sha1=$_actual"; \
    wget -q --tries=3 --timeout=60 -O "twitch-6.5.jar" "https://bmclapi2.bangbang93.com/maven/tv/twitch/twitch/6.5/twitch-6.5.jar" || wget -q --tries=2 --timeout=45 -O "twitch-6.5.jar" "https://libraries.minecraft.net/tv/twitch/twitch/6.5/twitch-6.5.jar"; \
    _expect="320a2dfd18513a5f41b4e75729df684488cbd925"; \
    _actual=$(sha1sum "twitch-6.5.jar" | awk '{print $1}'); \
    if [ "$_actual" != "$_expect" ]; then \
      echo "!!! SHA1 FAIL twitch-6.5.jar: want $_expect got $_actual !!!" >&2; exit 1; \
    fi; \
    echo "twitch-6.5.jar: OK, sha1=$_actual"; \
    _CNT=$(ls /mc/lib/*.jar 2>/dev/null | wc -l); echo "[build] lib jars count: $_CNT"; test "$_CNT" -ge 32

# 1.8.8 客户端 jar：优先 BMCLAPI 国内镜像，SHA1 基于 BMCLAPI 实际分发版本
# Mojang 官方 CDN 的旧版 1.8.8 对象哈希已从存储移除（404），BMCLAPI 当前 1.8.8 client.jar = 0983f08b...
RUN set -eux; \
    MC_SHA1="0983f08be6a4e624f5d85689d1aca869ed99c738"; \
    MC_SHA1_PREFIX=$(echo "$MC_SHA1" | cut -c1-2); \
    MC_URL_1="https://bmclapi2.bangbang93.com/version/1.8.8/client"; \
    MC_URL_2="https://bmclapi2.bangbang93.com/versions/1.8.8/client.jar"; \
    MC_URL_3="https://bmclapi2.bangbang93.com/assets/${MC_SHA1_PREFIX}/${MC_SHA1}"; \
    MC_URL_4="https://bmclapi2.bangbang93.com/v1/objects/${MC_SHA1}/client.jar"; \
    MC_URL_5="https://piston-data.mojang.com/v1/objects/${MC_SHA1}/client.jar"; \
    MC_URL_6="https://launcher.mojang.com/v1/objects/${MC_SHA1}/client.jar"; \
    wget -q --tries=3 --timeout=120 -O /mc/client.jar "$MC_URL_1" || \
    wget -q --tries=3 --timeout=120 -O /mc/client.jar "$MC_URL_2" || \
    wget -q --tries=2 --timeout=60  -O /mc/client.jar "$MC_URL_3" || \
    wget -q --tries=2 --timeout=60  -O /mc/client.jar "$MC_URL_4" || \
    wget -q --tries=2 --timeout=30  -O /mc/client.jar "$MC_URL_5" || \
    wget -q --tries=2 --timeout=30  -O /mc/client.jar "$MC_URL_6"; \
    ACTUAL_SHA1=$(sha1sum /mc/client.jar | awk '{print $1}'); \
    if [ "$ACTUAL_SHA1" != "$MC_SHA1" ]; then \
      echo "!!! SHA1 MISMATCH: want $MC_SHA1, got $ACTUAL_SHA1 !!!" >&2; \
      rm -f /mc/client.jar; \
      exit 1; \
    fi; \
    echo "[build] client.jar OK: $(stat -c%s /mc/client.jar) bytes, sha1=$ACTUAL_SHA1"

# 1.8.8 资源索引（assets/indexes/1.8.json）
# 注意：MC 1.8.x 系列共用资源索引 id="1.8"（不是版本号 1.8.8）。缺失该文件会导致
# 客户端报 "Can't find the resource index file" 并连服失败；启动参数需用
# --assetIndex "1.8" 与之对应。
# 只取索引即可：缺失的声音/语言对象只会产生 "unknown soundEvent" 警告，不影响录制画面，
# 因此无需下载上百 MB 的完整 assets。
RUN set -eux; \
    mkdir -p /mc/assets/indexes; \
    IDX_SHA1="f6ad102bcaa53b1a58358f16e376d548d44933ec"; \
    wget -q --tries=3 --timeout=60 -O /mc/assets/indexes/1.8.json \
      "https://launchermeta.mojang.com/v1/packages/${IDX_SHA1}/1.8.json" || \
    wget -q --tries=3 --timeout=60 -O /mc/assets/indexes/1.8.json \
      "https://bmclapi2.bangbang93.com/indexes/1.8.json" || \
    wget -q --tries=2 --timeout=45 -O /mc/assets/indexes/1.8.json \
      "https://piston-meta.mojang.com/v1/packages/${IDX_SHA1}/1.8.json"; \
    _sz=$(stat -c%s /mc/assets/indexes/1.8.json); \
    echo "[build] assets/indexes/1.8.json = ${_sz} bytes"; \
    test "$_sz" -gt 10000

COPY entrypoint.sh /mc/entrypoint.sh
COPY healthcheck.sh /mc/healthcheck.sh
COPY run-mc.sh /mc/run-mc.sh
COPY observerctl.py /mc/observerctl.py
COPY xorg-dummy.conf /mc/xorg-dummy.conf
# Temurin JRE 8：MC 1.8.8 的原生 Java 运行时（LWJGL2/netty4.0 与 Java17 不兼容导致连服断线）
# jdk8/ 目录 = build context 下已解压的 JRE（bin/java、lib/ 等）
COPY jdk8 /mc/jdk8

RUN chmod +x /mc/entrypoint.sh /mc/healthcheck.sh /mc/run-mc.sh /mc/observerctl.py \
    && chmod -R +x /mc/jdk8/bin 2>/dev/null || true

EXPOSE 8080 5900

# ---- runtime defaults ----
# OpenGL: 强制 Mesa llvmpipe 软件光栅化（容器无 /dev/dri）
#   LIBGL_ALWAYS_SOFTWARE=1 禁用 DRI hw 路径，llvmpipe 提供 GL 2.1+ compat profile
# JDK 17 完整 JRE 路径：供 liblwjgl64.so 解析 libjawt.so
ENV DISPLAY=:99 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    MESA_GL_VERSION_OVERRIDE=3.3COMPAT \
    MESA_GLSL_VERSION_OVERRIDE=330 \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    LD_LIBRARY_PATH=/usr/lib/jvm/java-17-openjdk-amd64/lib:/usr/lib/jvm/java-17-openjdk-amd64/lib/server:/mc/lib/natives \
    OBSERVER_ID=1 \
    USERNAME=ReplayObserver_1 \
    UUID=ffffffff-ffff-ffff-ffff-fffffffffff1 \
    MC_SERVER_HOST=127.0.0.1 \
    MC_SERVER_PORT=25565 \
    VNC_ENABLE=false \
    VNC_PORT=5900 \
    OBSERVER_PORT=8080 \
    HLS_DIR=/hls \
    WIDTH=1280 \
    HEIGHT=720

HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=180s \
    CMD /mc/healthcheck.sh || exit 1

ENTRYPOINT ["/mc/entrypoint.sh"]

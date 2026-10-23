# Protocol test

Checks the pairing and remote code against a simulated TV built from the
reference protobuf definitions in the `androidtvremote2` Python package. No
Android SDK or real TV needed.

    pip install androidtvremote2
    mkdir -p /tmp/tv && mkdir -p /tmp/tv-classes
    javac -d /tmp/tv-classes $(find app/src/main/java -path '*protocol*' -name '*.java') tools/ProtocolTest.java
    java -cp /tmp/tv-classes ProtocolTest gen /tmp/tv
    python tools/fake_tv.py /tmp/tv/client.pem /tmp/tv &
    java -cp /tmp/tv-classes ProtocolTest run /tmp/tv

The fake TV listens on ports 6466 and 6467 of 127.0.0.1.

## Vestel Smart Center TVs

    python tools/fake_vestel_tv.py &
    javac -d /tmp/tv-classes $(find app/src/main/java -path '*protocol*' -name '*.java') tools/VestelTest.java
    java -cp /tmp/tv-classes VestelTest

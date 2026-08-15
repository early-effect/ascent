package ascent.js

import zio.test.*

object DevReloadSpec extends ZIOSpecDefault:

  def spec = suite("DevReload")(
    test("localhost, 127.0.0.1, and IPv6 loopback are local") {
      assertTrue(
        DevReload.isLocalHost("localhost"),
        DevReload.isLocalHost("127.0.0.1"),
        DevReload.isLocalHost("[::1]"),
      )
    },
    test("any other hostname is not local") {
      assertTrue(
        !DevReload.isLocalHost("example.com"),
        !DevReload.isLocalHost("earlyeffect.rocks"),
        !DevReload.isLocalHost(""),
      )
    },
    test("install does not throw when the reload endpoint is missing") {
      val _ = DevReload.install("/__ascent/missing-reload")
      assertTrue(true)
    },
  )
end DevReloadSpec

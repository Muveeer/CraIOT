package com.livecontrol.iotmod.data

import org.junit.Assert.*
import org.junit.Test

class ProfileValidatorTest {
    private fun ok(prefix: String = "/ver407") = ProfileValidator.validate("Live", "live-control.com", "18883", "/ws", prefix)
    @Test fun valid_profile_passes() { assertNull(ok()) }
    @Test fun vermax_prefix_passes() { assertNull(ok("/vermax")) }
    @Test fun nested_prefix_rejected() { assertNotNull(ok("/a/b")) }
    @Test fun prefix_without_slash_rejected() { assertNotNull(ok("ver407")) }
    @Test fun port_out_of_range() { assertNotNull(ProfileValidator.validate("L", "h", "70000", "/ws", "/v")) }
    @Test fun port_not_number() { assertNotNull(ProfileValidator.validate("L", "h", "abc", "/ws", "/v")) }
    @Test fun empty_host() { assertNotNull(ProfileValidator.validate("L", " ", "1883", "", "/v")) }
    @Test fun path_must_start_with_slash() { assertNotNull(ProfileValidator.validate("L", "h", "1883", "ws", "/v")) }
    @Test fun empty_path_allowed_for_tcp() { assertNull(ProfileValidator.validate("L", "h", "1883", "", "/v")) }
}

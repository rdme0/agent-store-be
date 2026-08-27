package com.agentstore.agent.config

import com.agentstore.agent.model.entity.User
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.repository.UserRepository
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments

class DevIdentityInitializerTest {
    @Test
    fun `dev initializer creates only the reserved identity for an empty developer registry`() {
        val developers = mock(DeveloperRepository::class.java)
        val users = mock(UserRepository::class.java)
        `when`(developers.count()).thenReturn(0L)
        `when`(users.findById(any())).thenReturn(Optional.empty())
        val user = User(
            UUID.fromString("00000000-0000-0000-0000-00000000d000"),
            "agent-store-demo-catalog",
        )
        `when`(users.save(any())).thenReturn(user)

        DevIdentityInitializer(
            developerRepository = developers,
            userRepository = users,
        ).run(DefaultApplicationArguments())

        verify(users).save(any())
        verify(developers).save(any())
    }

    @Test
    fun `dev initializer leaves a nonempty developer registry unchanged`() {
        val developers = mock(DeveloperRepository::class.java)
        val users = mock(UserRepository::class.java)
        `when`(developers.count()).thenReturn(1L)

        DevIdentityInitializer(
            developerRepository = developers,
            userRepository = users,
        ).run(DefaultApplicationArguments())

        verifyNoInteractions(users)
    }
}

package com.agentstore.agent.config

import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.entity.User
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.repository.UserRepository
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class DevIdentityInitializerTest {
    @Test
    fun `dev initializer creates the reserved identity for an empty developer registry`() {
        val users = linkedMapOf<UUID, User>()
        val developers = linkedMapOf<UUID, Developer>()

        runInitializer(users, developers)

        val user = requireNotNull(users[DevIdentityInitializer.DEMO_USER_ID])
        assertEquals(DevIdentityInitializer.DEMO_USER_EXTERNAL_ID, user.externalId)
        val developer = requireNotNull(developers[DevIdentityInitializer.DEMO_DEVELOPER_ID])
        assertEquals(DevIdentityInitializer.DEMO_USER_ID, developer.user.id)
    }

    @Test
    fun `dev initializer preserves existing developers and adds the reserved identity`() {
        val existingUserId = UUID.fromString("00000000-0000-0000-0000-00000000e001")
        val existingUser = User(existingUserId, "existing-user")
        val existingDeveloper = Developer(
            UUID.fromString("00000000-0000-0000-0000-00000000e002"),
            existingUser,
            "Existing developer",
        )
        val users = linkedMapOf(existingUserId to existingUser)
        val developers = linkedMapOf(existingDeveloper.id to existingDeveloper)

        runInitializer(users, developers)

        assertEquals(2, developers.size)
        assertEquals(existingDeveloper, developers[existingDeveloper.id])
        assertEquals(2, users.size)
        requireNotNull(developers[DevIdentityInitializer.DEMO_DEVELOPER_ID])
    }

    private fun runInitializer(users: MutableMap<UUID, User>, developers: MutableMap<UUID, Developer>) {
        DevIdentityInitializer(
            developerRepository = fakeDeveloperRepository(developers),
            userRepository = fakeUserRepository(users),
        ).run(DefaultApplicationArguments())
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeDeveloperRepository(values: MutableMap<UUID, Developer>): DeveloperRepository {
        return Proxy.newProxyInstance(
            DeveloperRepository::class.java.classLoader,
            arrayOf(DeveloperRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "findById" -> Optional.ofNullable(values[arguments!![0] as UUID])
                "save" -> (arguments!![0] as Developer).also { values[it.id] = it }
                "count" -> values.size.toLong()
                "toString" -> "fake-developer-repository"
                else -> throw UnsupportedOperationException("Unsupported fake repository method: ${method.name}")
            }
        } as DeveloperRepository
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeUserRepository(values: MutableMap<UUID, User>): UserRepository {
        return Proxy.newProxyInstance(
            UserRepository::class.java.classLoader,
            arrayOf(UserRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "findById" -> Optional.ofNullable(values[arguments!![0] as UUID])
                "findByExternalId" -> values.values.firstOrNull { it.externalId == arguments!![0] as String }
                "save" -> (arguments!![0] as User).also { values[it.id] = it }
                "count" -> values.size.toLong()
                "toString" -> "fake-user-repository"
                else -> throw UnsupportedOperationException("Unsupported fake repository method: ${method.name}")
            }
        } as UserRepository
    }
}

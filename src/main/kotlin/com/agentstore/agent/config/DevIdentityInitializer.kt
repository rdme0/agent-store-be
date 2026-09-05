package com.agentstore.agent.config

import com.agentstore.agent.model.entity.Developer
import com.agentstore.agent.model.entity.User
import com.agentstore.agent.repository.DeveloperRepository
import com.agentstore.agent.repository.UserRepository
import java.util.UUID
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DevIdentityInitializer(
    private val developerRepository: DeveloperRepository,
    private val userRepository: UserRepository,
) : ApplicationRunner {
    companion object {
        const val DEMO_USER_EXTERNAL_ID = "agent-store-demo-catalog"
        const val DEMO_DEVELOPER_NAME = "AgentStore Demo"
        val DEMO_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d000")
        val DEMO_DEVELOPER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000d001")
    }

    override fun run(args: ApplicationArguments) {
        registerDemoIdentity()
    }

    private fun registerDemoIdentity() {
        val user = userRepository.findById(DEMO_USER_ID).orElse(null)
            ?: userRepository.findByExternalId(DEMO_USER_EXTERNAL_ID)?.also { existing ->
                check(existing.id == DEMO_USER_ID) {
                    "The reserved demo external identity belongs to another user id"
                }
            }
            ?: userRepository.save(User(DEMO_USER_ID, DEMO_USER_EXTERNAL_ID))
        check(user.externalId == DEMO_USER_EXTERNAL_ID) {
            "The reserved demo user id belongs to another external identity"
        }
        val developer = developerRepository.findById(DEMO_DEVELOPER_ID).orElse(null)
        if (developer == null) {
            developerRepository.save(Developer(DEMO_DEVELOPER_ID, user, DEMO_DEVELOPER_NAME))
            return
        }
        check(developer.user.id == DEMO_USER_ID) {
            "The reserved demo developer id belongs to another user"
        }
    }
}

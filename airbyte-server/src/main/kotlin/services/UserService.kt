/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.api.server.services

import io.airbyte.api.client.model.generated.WorkspaceReadList
import io.airbyte.api.model.generated.ListWorkspacesByUserRequestBody
import io.airbyte.api.server.constants.HTTP_RESPONSE_BODY_DEBUG_MESSAGE
import io.airbyte.api.server.errorHandlers.ConfigClientErrorHandler
import io.airbyte.commons.server.handlers.UserHandler
import io.airbyte.commons.server.handlers.WorkspacesHandler
import io.micronaut.context.annotation.Secondary
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.UUID

interface UserService {
  fun getAllWorkspaceIdsForUser(userId: UUID): List<UUID>
}

const val CUSTOMER_ID = "customer_id"
const val USER_ID = "user_id"

@Singleton
@Secondary
open class UserServiceImpl(
  private val workspacesHandler: WorkspacesHandler,
  private val userHandler: UserHandler,
) : UserService {
  companion object {
    private val log = LoggerFactory.getLogger(UserServiceImpl::class.java)
  }

  override fun getAllWorkspaceIdsForUser(userId: UUID): List<UUID> {
    val listWorkspacesByUserRequestBody = ListWorkspacesByUserRequestBody().userId(userId)
    val result =
      kotlin.runCatching { workspacesHandler.listWorkspacesByUser(listWorkspacesByUserRequestBody) }
        .onFailure {
          log.error("Error for listWorkspacesByUser", it)
          ConfigClientErrorHandler.handleError(it, "airbyte-user")
        }

    log.debug(HTTP_RESPONSE_BODY_DEBUG_MESSAGE + result)
    val workspaceReadList: WorkspaceReadList = result.getOrDefault(WorkspaceReadList().workspaces(emptyList())) as WorkspaceReadList
    return workspaceReadList.workspaces.map { it.workspaceId }
  }
}

package com.easycoderemote.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE profileId = :profileId ORDER BY lastUpdated DESC")
    fun observeSessions(profileId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE profileId = :profileId AND id = :sessionId")
    fun observeSession(profileId: String, sessionId: String): Flow<SessionEntity?>

    @Query("SELECT id FROM sessions WHERE profileId = :profileId")
    suspend fun ids(profileId: String): List<String>

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE profileId = :profileId AND id = :sessionId")
    suspend fun delete(profileId: String, sessionId: String)

    @Query("SELECT id FROM sessions WHERE profileId = :profileId AND archived = 1 ORDER BY lastUpdated DESC LIMIT 50 OFFSET 50")
    suspend fun archivedBeyond(profileId: String): List<String>

    @Query("DELETE FROM sessions WHERE profileId = :profileId AND id IN (:ids)")
    suspend fun deleteAll(profileId: String, ids: List<String>)

    @Query("DELETE FROM sessions WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE profileId = :profileId AND sessionId = :sessionId ORDER BY seq ASC")
    fun observeMessages(profileId: String, sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE profileId = :profileId AND sessionId = :sessionId AND id = :messageId")
    fun observeMessage(profileId: String, sessionId: String, messageId: String): Flow<MessageEntity?>

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT MAX(seq) FROM messages WHERE profileId = :profileId AND sessionId = :sessionId")
    suspend fun maxSeq(profileId: String, sessionId: String): Long?

    @Query("DELETE FROM messages WHERE profileId = :profileId AND sessionId = :sessionId AND id = :messageId")
    suspend fun delete(profileId: String, sessionId: String, messageId: String)

    @Query("DELETE FROM messages WHERE profileId = :profileId AND sessionId = :sessionId")
    suspend fun clearSession(profileId: String, sessionId: String)

    @Query(
        """DELETE FROM messages WHERE profileId = :profileId AND sessionId = :sessionId AND id NOT IN
           (SELECT id FROM messages WHERE profileId = :profileId AND sessionId = :sessionId ORDER BY seq DESC LIMIT 200)""",
    )
    suspend fun trimSession(profileId: String, sessionId: String)

    @Query("DELETE FROM messages WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)
}

@Dao
interface PartDao {
    @Query("SELECT * FROM parts WHERE profileId = :profileId AND sessionId = :sessionId AND messageId = :messageId ORDER BY seq ASC")
    fun observeParts(profileId: String, sessionId: String, messageId: String): Flow<List<PartEntity>>

    @Query("SELECT * FROM parts WHERE profileId = :profileId AND sessionId = :sessionId AND messageId = :messageId AND id = :partId")
    fun observePart(profileId: String, sessionId: String, messageId: String, partId: String): Flow<PartEntity?>

    @Upsert
    suspend fun upsert(part: PartEntity)

    @Query("SELECT MAX(seq) FROM parts WHERE profileId = :profileId AND sessionId = :sessionId AND messageId = :messageId")
    suspend fun maxSeq(profileId: String, sessionId: String, messageId: String): Long?

    @Query("DELETE FROM parts WHERE profileId = :profileId AND sessionId = :sessionId AND messageId = :messageId AND id = :partId")
    suspend fun delete(profileId: String, sessionId: String, messageId: String, partId: String)

    @Query("DELETE FROM parts WHERE profileId = :profileId AND sessionId = :sessionId")
    suspend fun clearSession(profileId: String, sessionId: String)

    @Query("DELETE FROM parts WHERE profileId = :profileId AND sessionId = :sessionId AND messageId = :messageId")
    suspend fun clearMessage(profileId: String, sessionId: String, messageId: String)

    @Query("DELETE FROM parts WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)
}

@Dao
interface PendingItemDao {
    @Query("SELECT * FROM pending_items WHERE profileId = :profileId AND sessionId = :sessionId ORDER BY receivedAt ASC")
    fun observePending(profileId: String, sessionId: String): Flow<List<PendingItemEntity>>

    @Upsert
    suspend fun upsert(item: PendingItemEntity)

    @Query("DELETE FROM pending_items WHERE profileId = :profileId AND sessionId = :sessionId AND id = :id")
    suspend fun delete(profileId: String, sessionId: String, id: String)

    @Query("DELETE FROM pending_items WHERE profileId = :profileId AND sessionId = :sessionId")
    suspend fun clearSession(profileId: String, sessionId: String)

    @Query("DELETE FROM pending_items WHERE profileId = :profileId AND sessionId = :sessionId AND kind = :kind")
    suspend fun clearKind(profileId: String, sessionId: String, kind: String)

    @Query("DELETE FROM pending_items WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)
}
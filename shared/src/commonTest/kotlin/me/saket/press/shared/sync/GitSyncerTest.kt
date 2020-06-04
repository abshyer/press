package me.saket.press.shared.sync

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNull
import com.soywiz.klock.DateTime
import com.soywiz.klock.hours
import com.soywiz.klock.milliseconds
import com.soywiz.klock.seconds
import me.saket.kgit.GitTreeDiff.Change.Add
import me.saket.kgit.PushResult.Failure
import me.saket.kgit.RealGit
import me.saket.kgit.SshConfig
import me.saket.press.data.shared.Note
import me.saket.press.data.shared.NoteQueries
import me.saket.press.shared.BuildKonfig
import me.saket.press.shared.db.BaseDatabaeTest
import me.saket.press.shared.fakedata.fakeNote
import me.saket.press.shared.note.archivedAt
import me.saket.press.shared.note.deletedAt
import me.saket.press.shared.sync.git.DeviceInfo
import me.saket.press.shared.sync.git.File
import me.saket.press.shared.sync.git.GitSyncer
import me.saket.press.shared.sync.git.UtcTimestamp
import me.saket.press.shared.sync.git.repository
import me.saket.press.shared.time.FakeClock
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * See AndroidGitSyncerTest.
 */
abstract class GitSyncerTest(private val deviceInfo: DeviceInfo) : BaseDatabaeTest() {

  private val noteQueries get() = database.noteQueries
  private val gitDirectory = File(deviceInfo.appStorage, "git")
  private val git = RealGit()
  private val syncer: GitSyncer
  private val clock = FakeClock()

  init {
    println()
    git.ssh = SshConfig(privateKey = BuildKonfig.GITHUB_SSH_PRIV_KEY)

    // Git timestamps are precise upto 1 second. Throw away milliseconds.
    clock.advanceTimeBy(1.seconds - clock.nowUtc().milliseconds.milliseconds)

    syncer = GitSyncer(
        git = git.repository(gitDirectory),
        database = database,
        deviceInfo = deviceInfo,
        clock = clock
    )
    syncer.setRemote("git@github.com:saket/PressSyncPlayground.git")
  }

  @AfterTest
  fun cleanUp() {
    deviceInfo.appStorage.delete(recursively = true)
  }

  @Test fun `pull notes from a non-empty repo`() {
    if (BuildKonfig.GITHUB_SSH_PRIV_KEY.isBlank()) {
      return
    }

    val firstCommitTime = clock.nowUtc() - 10.hours
    val secondCommitTime = clock.nowUtc()

    // Given: Remote repository has some notes over multiple commits.
    RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = firstCommitTime,
          files = listOf(
              "note_1.md" to "# The Witcher",
              "note_2.md" to "# Uncharted: The Lost Legacy"
          )
      )
      commitFiles(
          message = "Second commit",
          time = secondCommitTime,
          files = listOf(
              "note_3.md" to "# Overcooked",
              "note_4.md" to "# The Last of Us"
          )
      )
      forcePush()
    }

    // Given: User hasn't saved any notes on this device yet.
    assertThat(noteQueries.notes().executeAsList()).isEmpty()

    syncer.sync()

    // Check that the notes were pulled and saved into DB.
    val notesAfterSync = noteQueries.notes().executeAsList()
    assertThat(notesAfterSync.map { it.content }).containsOnly(
        "# The Witcher",
        "# Uncharted: The Lost Legacy",
        "# Overcooked",
        "# The Last of Us"
    )

    notesAfterSync.first { it.content == "# The Witcher" }.apply {
      assertThat(createdAt).isEqualTo(firstCommitTime)
      assertThat(updatedAt).isEqualTo(firstCommitTime)
      assertThat(archivedAt).isNull()
      assertThat(deletedAt).isNull()
    }

    notesAfterSync.first { it.content == "# The Last of Us" }.apply {
      assertThat(createdAt).isEqualTo(secondCommitTime)
    }
  }

  @Test fun `push notes to an empty repo`() {
    if (BuildKonfig.GITHUB_SSH_PRIV_KEY.isBlank()) {
      return
    }

    // Given: Remote repository is empty.
    val remote = RemoteRepositoryRobot {
      commitFiles(message = "Emptiness", files = emptyList())
      forcePush()
    }

    // Given: This device has some notes.
    noteQueries.testInsert(
        fakeNote(
            content = "# Nicolas Cage \nis a national treasure",
            updatedAt = clock.nowUtc()
        ),
        fakeNote(
            content = "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures",
            updatedAt = clock.nowUtc()
        )
    )

    syncer.sync()

    // Check that the local note(s) were pushed to remote.
    with(remote.fetchFiles()) {
      assertThat(this).containsOnly(
          "nicolas_cage.md" to "# Nicolas Cage \nis a national treasure",
          "witcher_3.md" to "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
      )
    }
  }

  @Test fun `merge local and remote notes without conflicts`() {
    if (BuildKonfig.GITHUB_SSH_PRIV_KEY.isBlank()) {
      return
    }

    // Given: Remote and local notes are saved in mixed order.
    val remoteTime1 = clock.nowUtc() - 10.hours
    val localTime1 = remoteTime1 + 2.hours
    val remoteTime2 = remoteTime1 + 4.hours
    val localTime2 = clock.nowUtc()

    // Given: Remote repository has some notes over multiple commits.
    val remote = RemoteRepositoryRobot {
      commitFiles(
          message = "First commit",
          time = remoteTime1,
          files = listOf("note_1.md" to "# Uncharted: The Lost Legacy")
      )
      commitFiles(
          message = "Second commit",
          time = remoteTime2,
          files = listOf("note_2.md" to "# The Last of Us")
      )
      forcePush()
    }

    // Given: This device has some notes.
    noteQueries.testInsert(
        fakeNote(
            content = "# Nicolas Cage \nis a national treasure",
            updatedAt = localTime1
        ),
        fakeNote(
            content = "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures",
            updatedAt = localTime2
        )
    )

    syncer.sync()

    // Check: both local and remote have same notes with same timestamps.
    val localNotes = noteQueries.notes()
        .executeAsList()
        .sortedBy { it.updatedAt }

    assertThat(localNotes.map { it.content }).containsExactly(
        "# Uncharted: The Lost Legacy",
        "# Nicolas Cage \nis a national treasure",
        "# The Last of Us",
        "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
    )

    with(remote.fetchFiles()) {
      assertThat(this).containsOnly(
          "note_1.md" to "# Uncharted: The Lost Legacy",
          "note_2.md" to "# The Last of Us",
          "nicolas_cage.md" to "# Nicolas Cage \nis a national treasure",
          "witcher_3.md" to "# Witcher 3 \nKings Die, Realms Fall, But Magic Endures"
      )
    }
  }

//  @Test fun `resolve conflicts when content has changed but not the file name`() {
//    // TODO
//  }
//
//  @Test fun `resolve conflicts when both the content and file name have changed`() {
//    // TODO
//  }

  private inner class RemoteRepositoryRobot(prepare: RemoteRepositoryRobot.() -> Unit) {
    private val directory = File(deviceInfo.appStorage, "temp").apply { makeDirectory() }
    private val gitRepo = git.repository(directory)

    init {
      gitRepo.addRemote("origin", "git@github.com:saket/PressSyncPlayground.git")
      prepare()
    }

    fun forcePush() {
      assertThat(gitRepo.push(force = true)).isNotInstanceOf(Failure::class)
    }

    fun commitFiles(message: String, time: DateTime? = null, files: List<Pair<String, String>>) {
      files.forEach { (name, body) ->
        File(directory.path, name).write(body)
      }
      gitRepo.addAll()
      gitRepo.commit(message, timestamp = UtcTimestamp(time ?: clock.nowUtc()), allowEmpty = true)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun fetchFiles(): List<Pair<String, String>> {
      gitRepo.pull(rebase = true)
      val head = gitRepo.headCommit()!!
      val diffs = gitRepo.diffBetween(from = null, to = head)
      return buildList {
        for (diff in diffs) {
          check(diff is Add)
          add(diff.path to File(directory, diff.path).read())
        }
      }
    }
  }
}

private fun NoteQueries.testInsert(vararg notes: Note.Impl) {
  notes.forEach { testInsert(it) }
}
package dev.spelfrog.getsynced.ui

import dev.spelfrog.getsynced.auth.GitHubRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteHelpersTest {
    private fun repo(name: String) = GitHubRepo("spelfrog/$name", "https://github.com/spelfrog/$name.git", "main", true, "", "")

    @Test
    fun sshRemotesBecomeHttps() {
        assertEquals("https://github.com/spelfrog/Obisdian-Vault.git", sshToHttps("git@github.com:spelfrog/Obisdian-Vault.git"))
        assertEquals("https://gitlab.com/a/b.git", sshToHttps("ssh://git@gitlab.com:22/a/b.git"))
        assertEquals("https://github.com/a/b.git", sshToHttps("https://github.com/a/b.git"))
    }

    @Test
    fun folderNameMatchesRepo() {
        val repos = listOf(repo("getSynced"), repo("Obisdian-Vault"), repo("notes"))
        assertEquals("spelfrog/Obisdian-Vault", matchRepoForFolder("Obisdian Vault", repos)?.fullName)
        assertEquals("spelfrog/Obisdian-Vault", matchRepoForFolder("obisdian", repos)?.fullName)
        assertEquals("spelfrog/notes", matchRepoForFolder("Notes", repos)?.fullName)
        assertNull(matchRepoForFolder("Documents", repos))
        assertNull(matchRepoForFolder("ab", repos))
    }
}

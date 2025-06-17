package com.celstech.satendroid.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LocalFileNavigationManagerTest {

    private lateinit var navigationManager: LocalFileNavigationManager

    @Before
    fun setUp() {
        navigationManager = LocalFileNavigationManager()
    }

    @Test
    fun `navigateToFolder should add current path to history`() {
        // Given
        val currentPath = "folder1"
        val currentHistory = listOf("", "root")
        val targetPath = "folder1/subfolder"

        // When
        val result = navigationManager.navigateToFolder(currentPath, currentHistory, targetPath)

        // Then
        assertEquals(targetPath, result.newPath)
        assertEquals(listOf("", "root", "folder1"), result.newHistory)
    }

    @Test
    fun `navigateBack should return to previous path`() {
        // Given
        val currentHistory = listOf("", "root", "folder1")

        // When
        val result = navigationManager.navigateBack(currentHistory)

        // Then
        assertEquals("folder1", result?.newPath)
        assertEquals(listOf("", "root"), result?.newHistory)
    }

    @Test
    fun `navigateBack should return null when history is empty`() {
        // Given
        val currentHistory = emptyList<String>()

        // When
        val result = navigationManager.navigateBack(currentHistory)

        // Then
        assertNull(result)
    }

    @Test
    fun `formatDisplayPath should format empty path correctly`() {
        // Given
        val path = ""

        // When
        val result = navigationManager.formatDisplayPath(path)

        // Then
        assertEquals("📁 Local Files", result)
    }

    @Test
    fun `formatDisplayPath should format non-empty path correctly`() {
        // Given
        val path = "folder1/subfolder"

        // When
        val result = navigationManager.formatDisplayPath(path)

        // Then
        assertEquals("📁 folder1/subfolder", result)
    }
}

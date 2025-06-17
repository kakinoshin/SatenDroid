package com.celstech.satendroid.selection

import com.celstech.satendroid.ui.models.LocalItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SelectionManagerTest {

    private lateinit var selectionManager: SelectionManager
    private lateinit var testZipFile: LocalItem.ZipFile
    private lateinit var testFolder: LocalItem.Folder

    @Before
    fun setUp() {
        selectionManager = SelectionManager()
        
        testZipFile = LocalItem.ZipFile(
            name = "test.zip",
            path = "test.zip",
            lastModified = System.currentTimeMillis(),
            size = 1024L,
            file = File("test.zip")
        )
        
        testFolder = LocalItem.Folder(
            name = "testFolder",
            path = "testFolder",
            lastModified = System.currentTimeMillis(),
            zipCount = 2
        )
    }

    @Test
    fun `enterSelectionMode should enable selection mode with initial item`() {
        // When
        val result = selectionManager.enterSelectionMode(testZipFile)

        // Then
        assertTrue(result.isSelectionMode)
        assertEquals(setOf(testZipFile), result.selectedItems)
    }

    @Test
    fun `exitSelectionMode should disable selection mode and clear items`() {
        // When
        val result = selectionManager.exitSelectionMode()

        // Then
        assertFalse(result.isSelectionMode)
        assertTrue(result.selectedItems.isEmpty())
    }

    @Test
    fun `toggleItemSelection should add item when not selected`() {
        // Given
        val currentSelection = emptySet<LocalItem>()

        // When
        val result = selectionManager.toggleItemSelection(currentSelection, testZipFile)

        // Then
        assertEquals(setOf(testZipFile), result)
    }

    @Test
    fun `toggleItemSelection should remove item when already selected`() {
        // Given
        val currentSelection = setOf(testZipFile, testFolder)

        // When
        val result = selectionManager.toggleItemSelection(currentSelection, testZipFile)

        // Then
        assertEquals(setOf(testFolder), result)
    }

    @Test
    fun `selectAll should return all items as set`() {
        // Given
        val allItems = listOf(testZipFile, testFolder)

        // When
        val result = selectionManager.selectAll(allItems)

        // Then
        assertEquals(setOf(testZipFile, testFolder), result)
    }

    @Test
    fun `deselectAll should return empty set`() {
        // When
        val result = selectionManager.deselectAll()

        // Then
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getSelectionStatus should return None when no items selected`() {
        // Given
        val selectedItems = emptySet<LocalItem>()
        val allItems = listOf(testZipFile, testFolder)

        // When
        val result = selectionManager.getSelectionStatus(selectedItems, allItems)

        // Then
        assertEquals(SelectionManager.SelectionStatus.None, result)
    }

    @Test
    fun `getSelectionStatus should return All when all items selected`() {
        // Given
        val allItems = listOf(testZipFile, testFolder)
        val selectedItems = setOf(testZipFile, testFolder)

        // When
        val result = selectionManager.getSelectionStatus(selectedItems, allItems)

        // Then
        assertEquals(SelectionManager.SelectionStatus.All, result)
    }

    @Test
    fun `getSelectionStatus should return Partial when some items selected`() {
        // Given
        val allItems = listOf(testZipFile, testFolder)
        val selectedItems = setOf(testZipFile)

        // When
        val result = selectionManager.getSelectionStatus(selectedItems, allItems)

        // Then
        assertEquals(SelectionManager.SelectionStatus.Partial, result)
    }
}

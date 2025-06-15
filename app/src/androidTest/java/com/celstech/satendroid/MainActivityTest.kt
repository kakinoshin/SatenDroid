package com.celstech.satendroid

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun zipButtonExistsAndIsClickable() {
        // Find the ZIP button by its text
        composeTestRule.onNodeWithText("Select ZIP File").assertExists()
        
        // Click on the button
        composeTestRule.onNodeWithText("Select ZIP File").performClick()
        
        // Note: Testing file selection would require more complex setup with instrumentation
    }
}
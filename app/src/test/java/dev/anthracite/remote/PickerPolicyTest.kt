package dev.anthracite.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerPolicyTest {
    @Test
    fun `save document chooses a concrete MIME type`() {
        assertEquals("application/octet-stream", saveDocumentMimeType(emptyList()))
        assertEquals("application/octet-stream", saveDocumentMimeType(listOf("*/*", "image/*")))
        assertEquals("image/png", saveDocumentMimeType(listOf("image/*", "image/png", "application/pdf")))
        assertEquals("application/pdf", saveDocumentMimeType(listOf(" application/pdf ")))
        assertEquals("application/octet-stream", saveDocumentMimeType(listOf("bad", "text/")))
    }

    @Test
    fun `picker launches only SAF document actions for every mode`() {
        assertEquals("android.intent.action.OPEN_DOCUMENT", pickerAction(PickerMode.OPEN))
        assertEquals("android.intent.action.OPEN_DOCUMENT", pickerAction(PickerMode.OPEN_MULTIPLE))
        assertEquals("android.intent.action.CREATE_DOCUMENT", pickerAction(PickerMode.SAVE))
        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", pickerAction(PickerMode.OPEN_FOLDER))
        assertFalse(pickerAction(PickerMode.OPEN).contains("GET_CONTENT"))
    }

    @Test
    fun `picker requires content uri and rejects app-owned private providers`() {
        assertTrue(pickerUriAllowed("content://docs.example/document/1", "dev.example.app", PickerMode.OPEN, true, false))
        assertFalse(pickerUriAllowed("file:///data/user/0/dev.example.app/private", "dev.example.app", PickerMode.OPEN, true, false))
        assertFalse(pickerUriAllowed("content://dev.example.app.files/private/1", "dev.example.app", PickerMode.OPEN, true, false))
        assertFalse(pickerUriAllowed("content://user@docs.example/1", "dev.example.app", PickerMode.OPEN, true, false))
    }

    @Test
    fun `picker mode requires appropriate grant and shape`() {
        assertTrue(pickerUriAllowed("content://docs.example/document/new", "dev.example.app", PickerMode.SAVE, false, true))
        assertFalse(pickerUriAllowed("content://docs.example/document/new", "dev.example.app", PickerMode.SAVE, true, false))
        assertTrue(pickerUriAllowed("content://docs.example/tree/root", "dev.example.app", PickerMode.OPEN_FOLDER, true, false))
        assertFalse(pickerUriAllowed("content://docs.example/document/1", "dev.example.app", PickerMode.OPEN_FOLDER, true, false))
        assertFalse(pickerUriAllowed("content://docs.example/document/1", "dev.example.app", PickerMode.OPEN_MULTIPLE, false, true))
    }
}

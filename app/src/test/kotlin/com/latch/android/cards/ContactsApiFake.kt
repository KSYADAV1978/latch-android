package com.latch.android.cards

import com.latch.google.ContactRecord
import com.latch.google.ContactUpdate
import com.latch.google.ContactsApi

/**
 * The parts of [ContactsApi] a fake has to answer only if the test is about them.
 *
 * **Both throw, and that is the point.** CLAUDE.md records the rule the expensive way: *a fake
 * that answers by fixture rather than by matching cannot catch a matching bug* —
 * `findEventBySourceHash` in the fakes ignored its argument for as long as the real one was
 * broken. An `getContact` returning an empty record would let an FR-1231 test pass while
 * establishing nothing; one that throws makes a test that reaches it fail loudly and name the
 * fixture it is missing.
 *
 * Shared rather than repeated in five files, so a sixth method added to the interface is one edit
 * and not five places to forget.
 */
abstract class ContactsApiFake : ContactsApi {

    override suspend fun getContact(resourceName: String): ContactRecord =
        throw NotImplementedError("this fake has no FR-1231 identity fixture")

    override suspend fun updateContact(
        resourceName: String,
        etag: String,
        update: ContactUpdate,
    ): String = throw NotImplementedError("this fake has no FR-1231 identity fixture")
}

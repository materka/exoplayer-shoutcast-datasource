package se.materka.exoplayershoutcastdatasource

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable

/**
 * Copyright 2017 Mattias Karlsson

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Contains metadata about an item, such as the title, artist, etc.
 */
class ShoutcastMetadata : Parcelable {

    /**
     * Gets the bundle backing the metadata object. This is available to support
     * backwards compatibility. Apps should not modify the bundle directly.
     *
     * @return The Bundle backing this metadata.
     */
    val bundle: Bundle

    internal constructor(bundle: Bundle) {
        this.bundle = Bundle(bundle)
    }

    internal constructor(parcel: Parcel) {
        bundle = parcel.readBundle()
    }

    /**
     * Returns true if the given key is contained in the metadata
     *
     * @param key a String key
     * @return true if the key exists in this metadata, false otherwise
     */
    fun containsKey(key: String): Boolean {
        return bundle.containsKey(key)
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @param key The key the value is stored under
     * @return a CharSequence value, or null
     */
    fun getText(key: String): CharSequence? {
        return bundle.getCharSequence(key)
    }

    /**
     * Returns the value associated with the given key, or null if no mapping of
     * the desired type exists for the given key or a null value is explicitly
     * associated with the key.
     *
     * @param key The key the value is stored under
     * @return a String value, or null
     */
    fun getString(key: String): String? {
        val text = bundle.getCharSequence(key)
        return text?.toString()
    }

    /**
     * Returns the value associated with the given key, or 0L if no long exists
     * for the given key.
     *
     * @param key The key the value is stored under
     * @return a long value
     */
    fun getLong(key: String): Long {
        return bundle.getLong(key, 0)
    }


    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBundle(bundle)
    }

    /**
     * Get the number of fields in this metadata.
     *
     * @return The number of fields in the metadata.
     */
    fun size(): Int {
        return bundle.size()
    }

    /**
     * Returns a Set containing the Strings used as keys in this metadata.
     *
     * @return a Set of String keys
     */
    fun keySet(): Set<String> {
        return bundle.keySet()
    }

    /**
     * Use to build MediaMetadata objects. The system defined metadata keys must
     * use the appropriate data type.
     */
    class Builder {
        private val bundle: Bundle

        /**
         * Create an empty Builder. Any field that should be included in the
         * [ShoutcastMetadata] must be added.
         */
        constructor() {
            bundle = Bundle()
        }

        /**
         * Create a Builder using a [ShoutcastMetadata] instance to set the
         * initial values. All fields in the source metadata will be included in
         * the new metadata. Fields can be overwritten by adding the same key.
         *
         * @param source
         */
        constructor(source: ShoutcastMetadata) {
            bundle = Bundle(source.bundle)
        }


        /**
         * Put a CharSequence value into the metadata. Custom keys may be used.
         *
         * @param key The key for referencing this value
         * @param value The CharSequence value to store
         * @param default The default value to be used if value is null
         * @return The Builder to allow chaining
         */
        fun putText(key: String, value: CharSequence?, default: CharSequence = ""): Builder {
            bundle.putCharSequence(key, value ?: default)
            return this
        }

        /**
         * Put a String value into the metadata. Custom keys may be used.
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @param default The default value to be used if value is null
         * @return The Builder to allow chaining
         */
        fun putString(key: String, value: String?, default: String = ""): Builder {
            bundle.putCharSequence(key, value ?: default)
            return this
        }

        /**
         * Put a long value into the metadata. Custom keys may be used.
         *
         * @param key The key for referencing this value
         * @param value The String value to store
         * @param default The default value to be used if value is null
         * @return The Builder to allow chaining
         */
        fun putLong(key: String, value: Long?, default: Long = 0L): Builder {
            bundle.putLong(key, value ?: default)
            return this
        }


        /**
         * Creates a [ShoutcastMetadata] instance with the specified fields.
         *
         * @return The new ShoutcastMetadata instance
         */
        fun build(): ShoutcastMetadata {
            return ShoutcastMetadata(bundle)
        }
    }

    companion object {
        /**
         * The title of the media.
         */
        val METADATA_KEY_TITLE = "android.media.metadata.TITLE"

        /**
         * The artist of the media.
         */
        val METADATA_KEY_ARTIST = "android.media.metadata.ARTIST"

        /**
         * The show of the media.
         */
        val METADATA_KEY_SHOW = "android.media.metadata.SHOW"

        /**
         * The bitrate of the media.
         */
        val METADATA_KEY_BITRATE = "android.media.metadata.BITRATE"

        /**
         * The genre of the media.
         */
        val METADATA_KEY_GENRE = "android.media.metadata.GENRE"

        /**
         * The number of channels of the media.
         */
        val METADATA_KEY_CHANNELS = "android.media.metadata.CHANNELS"

        /**
         * The url of the media.
         */
        val METADATA_KEY_URL = "android.media.metadata.URL"

        /**
         * The station of the media.
         */
        val METADATA_KEY_STATION = "android.media.metadata.STATION"

        /**
         * The format of the media.
         */
        val METADATA_KEY_FORMAT = "android.media.metadata.FORMAT"

        val CREATOR: Parcelable.Creator<ShoutcastMetadata> = object : Parcelable.Creator<ShoutcastMetadata> {
            override fun createFromParcel(parcel: Parcel): ShoutcastMetadata {
                return ShoutcastMetadata(parcel)
            }

            override fun newArray(size: Int): Array<ShoutcastMetadata?> {
                return arrayOfNulls(size)
            }
        }
    }

}

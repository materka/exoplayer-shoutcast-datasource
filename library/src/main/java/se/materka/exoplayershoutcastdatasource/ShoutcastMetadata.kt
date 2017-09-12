package se.materka.exoplayershoutcastdatasource

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

data class ShoutcastMetadata(val artist: String? = null,
                             val title: String? = null,
                             val show: String? = null,
                             val channels: String? = null,
                             val bitrate: String? = null,
                             val station: String? = null,
                             val genre: String? = null,
                             val url: String? = null,
                             val format: String? = null) {

    override fun toString(): String {
        return "Artist:$artist\nSong:$title\nShow:$show\nChannels:$channels\nBitrate:$bitrate\nStation:$station\nGenre:$genre\nUrl:$url\nFormat:$format"
    }
}
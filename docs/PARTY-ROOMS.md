# Party Rooms

Party Rooms let multiple authenticated Aoede users listen to a shared queue with synchronized playback.

## Discovering rooms

Open **Home → Party** to see available rooms. The room list can show information such as:

- room name;
- host;
- listener count and capacity;
- current track;
- playback state.

Rooms are currently discoverable by signed-in regular users. Private rooms, passwords and invitation links are not currently part of the documented behavior.

## Create a room

1. Open **Home → Party**.
2. Select **Create room**.
3. Enter a room name.
4. Choose a capacity from **2 to 15 listeners**.
5. Optionally choose one of your playlists to seed the initial queue.
6. Choose whether guests may search the local library and add songs.
7. Create and open the room.

Each user can own one room at a time. Delete the existing room before creating another.

## Join and listen

Select a room from the Party list or Home shelf.

While connected, Aoede temporarily switches the player from your personal queue to the room's shared track and server-owned playback clock. Playback events such as play/pause, seek, track changes, media controls and reconnects are synchronized around the room state.

If the browser blocks autoplay, use the on-screen **Tap to start listening** prompt.

When you leave, Aoede restores the personal playback context that was active before joining, including the personal track/queue and playback settings handled by the client.

## Host and guest permissions

| Action | Host | Guest |
|---|---:|---:|
| Play / pause | Yes | Follows host |
| Seek | Yes | Follows host |
| Previous / next | Yes | Follows host |
| Add local-library songs | Yes | Only when enabled by host |
| Remove songs | Yes | No |
| Delete room | Yes | No |

## Federated rooms and songs

A federated room's queue is managed by its host server. Remote listeners can search the host's local library and its enabled federation catalogue, then add songs when the host allows guest queue edits. Song names containing quotes or other punctuation work through the same controls.

**Add to party** from a listener's own library also identifies the song's source server. Local numeric song IDs are not interchangeable between servers. The host must subscribe to that source and have an up-to-date catalogue before a listener-owned or third-server song can be queued. A missing subscription/catalogue produces an actionable error instead of selecting a different song with the same ID.

Update both the room host and listener servers to use this origin-aware flow. A host running an older party API can still return 404 on join/queue endpoints; updating only the listener does not add endpoints to the host. Remote listeners remain guests: only the local room owner can delete the room or control playback.

## Persistence and limits

- Rooms and queues survive application restarts.
- After a restart, room playback resumes in a paused state.
- Music stops when the last listener disconnects, while the empty room can remain available.
- A short disconnect grace period prevents a refresh or brief network interruption from immediately stopping the room.
- Multiple tabs from the same account count as one listener.
- A room queue supports up to **500 songs**.
- Party search includes music in the host's shared library and enabled federation catalogue; it does not initiate provider searches or downloads.
- Party endpoints require an authenticated regular user; service accounts and anonymous requests are rejected.

## Good screenshot for the README

For the public README, capture an **active** room rather than an empty list. Show the room name, current track, listeners and a queue long enough to communicate the feature immediately.

See [Screenshot Guide](SCREENSHOTS.md).

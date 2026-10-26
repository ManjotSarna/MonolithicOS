package com.joe.home;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself: Android only lets an app read other apps' media sessions (for Now Playing)
 * if it has a notification listener the user (here: the installer, via `cmd notification allow_listener`)
 * has allowed.
 */
public final class NowPlayingListener extends NotificationListenerService { }

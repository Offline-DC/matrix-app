package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.backend.smarttxt.R

/**
 * Loading placeholder for the SmartTxt boot screen — shows the dumb.co duck
 * (bundled as res/drawable-nodpi/duck_logo.png) instead of a bare spinner.
 * Rendered from the local resource, so it's instant and works offline.
 */
@Composable
fun DuckLoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.duck_logo),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

package com.source.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.source.client.R

@Composable
internal fun SourceColumn(
    vertical: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = vertical,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
internal fun Wordmark() = Text(
    stringResource(R.string.app_name),
    style = MaterialTheme.typography.displaySmall,
    fontWeight = FontWeight.SemiBold,
)

@Composable
internal fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error)

@Composable
internal fun SmallProgress() = CircularProgressIndicator(
    Modifier.height(20.dp),
    strokeWidth = 2.dp,
    color = Color.White,
)

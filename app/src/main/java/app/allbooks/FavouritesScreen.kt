package app.allbooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Екран «Закладки»: збережені книги. Натискання — сторінка книги. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    s: AppStrings,
    favourites: List<Favourite>,
    onBack: () -> Unit,
    onOpen: (Favourite) -> Unit,
    onRemove: (Favourite) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(s.favourites) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(s.back) }
                }
            )
        }
    ) { padding ->
        if (favourites.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(s.noFavourites, modifier = Modifier.padding(24.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(favourites, key = { it.entry.id }) { fav ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(fav) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        fav.entry.thumbnail?.let { cover ->
                            AsyncImage(
                                model = cover.href,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp, 72.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                fav.entry.title,
                                style = MaterialTheme.typography.titleMedium
                            )
                            val authors = fav.entry.authors
                                .joinToString(", ") { it.name }
                            if (authors.isNotBlank()) {
                                Text(
                                    authors,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        TextButton(onClick = { onRemove(fav) }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

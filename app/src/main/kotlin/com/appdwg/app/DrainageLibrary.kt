package com.appdwg.app

import com.appdwg.core.Display
import com.appdwg.core.StationFormat
import com.appdwg.core.drainage.CatalogAudit
import com.appdwg.core.drainage.DeviceBody
import com.appdwg.core.drainage.CatalogFormatException
import com.appdwg.core.drainage.CatalogLoader
import com.appdwg.core.drainage.CatalogReport
import com.appdwg.core.drainage.ConnectionPoint
import com.appdwg.core.drainage.DeviceAccess
import com.appdwg.core.drainage.DeviceCatalog
import com.appdwg.core.drainage.DeviceCategory
import com.appdwg.core.drainage.DeviceDef
import com.appdwg.core.drainage.Parameter
import com.appdwg.core.drainage.Quantities
import com.appdwg.core.drainage.SectionPoint
import com.appdwg.core.tr
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** IPR-736 device library. Browsing is open; placement and calculations ask for Pro. */
@Composable
fun DrainageLibraryDialog(
    isPro: Boolean,
    onPro: () -> Unit,
    onDismiss: () -> Unit,
    catalog: DeviceCatalog,
    catalogReady: Boolean,
    catalogNote: String?,
    onPlace: (DeviceCatalog, DeviceDef) -> Unit = { _, _ -> },
    onSuggestCulverts: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.safeBars().fillMaxWidth(0.96f),
        title = { Text(tr("Biblioteca de dispositivos"), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            DrainageLibraryBody(
                isPro, onPro, Modifier.fillMaxWidth().heightIn(max = 520.dp),
                catalog, catalogReady, catalogNote, onPlace,
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSuggestCulverts != null) GButton(tr("Sugerir bueiros"), onSuggestCulverts, outlined = true, modifier = Modifier.fillMaxWidth())
                GButton(tr("Fechar"), onDismiss, modifier = Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
fun DrainageLibraryBody(
    isPro: Boolean,
    onPro: () -> Unit,
    modifier: Modifier = Modifier,
    catalog: DeviceCatalog,
    catalogReady: Boolean,
    catalogNote: String?,
    onPlace: (DeviceCatalog, DeviceDef) -> Unit = { _, _ -> },
) {
    if (!catalogReady) {
        Text(tr("Abrindo a biblioteca de drenagem…"), modifier, maxLines = 3, overflow = TextOverflow.Ellipsis)
        return
    }
    if (catalogNote != null && catalog.devices.isEmpty()) {
        Text(catalogNote, modifier, maxLines = 4, overflow = TextOverflow.Ellipsis)
        return
    }
    var current by remember(catalog) { mutableStateOf(catalog) }
    var query by remember { mutableStateOf("") }
    var chapter by remember { mutableStateOf<Int?>(null) }
    var category by remember { mutableStateOf<DeviceCategory?>(null) }
    var family by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    LaunchedEffect(selected) { scroll.scrollTo(0) }
    val picked = selected?.let { current.device(it) }
    if (picked != null) {
        Column(
            modifier.verticalScroll(scroll).padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeviceDetail(current, picked, isPro, onPro, onBack = { selected = null }, onCatalog = { current = it }, onPlace = onPlace)
        }
    } else {
        // The album has hundreds of rows. A plain column composes every one and the
        // first open stops answering taps on a slow device.
        DeviceBrowser(
            current, query, chapter, category, family, isPro, modifier,
            onQuery = { query = it },
            onChapter = { chapter = it },
            onCategory = { category = it },
            onFamily = { family = it },
            onOpen = { selected = it },
            onCatalog = { current = it },
        )
    }
}

@Composable
private fun DeviceBrowser(
    catalog: DeviceCatalog,
    query: String,
    chapter: Int?,
    category: DeviceCategory?,
    family: String?,
    isPro: Boolean,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
    onChapter: (Int?) -> Unit,
    onCategory: (DeviceCategory?) -> Unit,
    onFamily: (String?) -> Unit,
    onOpen: (String) -> Unit,
    onCatalog: (DeviceCatalog) -> Unit,
) {
    val chapters = remember(catalog) { catalog.devices.map { it.chapter }.filter { it > 0 }.distinct().sorted() }
    val families = remember(catalog, chapter, category) {
        catalog.query("", chapter, category, null).map { it.family }.filter { it.isNotBlank() }.distinct().sorted()
    }
    val familyOn = family?.takeIf { it in families }
    val shown = remember(catalog, query, chapter, category, familyOn) {
        catalog.query(query, chapter, category, familyOn)
    }
    val userCount = catalog.devices.count { it.origin != catalog.origin }
    LazyColumn(
        modifier.padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Catálogo {0} · {1}", catalog.origin, catalog.generated), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(tr("{0} dispositivos", shown.size), style = MaterialTheme.typography.bodySmall)
                Text(tr("Catálogo offline: {0} dispositivos, cálculos no aparelho.", catalog.devices.size), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (isPro) CatalogAuditLine(catalog)
                if (userCount > 0) {
                    Text(tr("{0} dispositivos da biblioteca do usuário", userCount), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            OutlinedTextField(
                query,
                onQuery,
                modifier = Modifier.fillMaxWidth().testTag("drainage-search"),
                label = { Text(tr("Buscar por código ou nome"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Capítulo"), style = MaterialTheme.typography.titleSmall)
                ChoiceRow {
                    Choice(chapter == null, tr("Todos")) { onChapter(null) }
                    for (n in chapters) Choice(chapter == n, chapterTitle(catalog, n)) { onChapter(if (chapter == n) null else n) }
                }
                Text(tr("Categoria"), style = MaterialTheme.typography.titleSmall)
                ChoiceRow {
                    Choice(category == null, tr("Todos")) { onCategory(null) }
                    Choice(category == DeviceCategory.LINEAR, tr("Lineares")) { onCategory(if (category == DeviceCategory.LINEAR) null else DeviceCategory.LINEAR) }
                    Choice(category == DeviceCategory.POINT, tr("Pontuais")) { onCategory(if (category == DeviceCategory.POINT) null else DeviceCategory.POINT) }
                }
                Text(tr("Família"), style = MaterialTheme.typography.titleSmall)
                ChoiceRow {
                    Choice(familyOn == null, tr("Todos")) { onFamily(null) }
                    for (name in families) Choice(familyOn == name, name) { onFamily(if (familyOn == name) null else name) }
                }
            }
        }
        if (shown.isEmpty()) item { Text(tr("Nenhum dispositivo encontrado.")) }
        items(shown, key = { it.code }) { device -> DeviceRow(device) { onOpen(device.code) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (catalog.unitsNote.isNotBlank()) {
                    Text(catalog.unitsNote, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                ImportBlock(catalog, onCatalog)
                if (!isPro) {
                    Text(tr("O Pro libera o lançamento e os cálculos."), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CatalogAuditLine(catalog: DeviceCatalog) {
    var report by remember(catalog) { mutableStateOf<CatalogReport?>(null) }
    LaunchedEffect(catalog) {
        report = withContext(Dispatchers.Default) { CatalogAudit.check(catalog) }
    }
    val checked = report ?: return
    val text = if (checked.problems.isEmpty()) {
        tr("Catálogo conferido: {0} dispositivos com seção, sem pendência.", checked.sectioned)
    } else {
        tr("Catálogo: {0} dispositivos com pendência de cálculo.", checked.problems.size)
    }
    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DeviceRow(device: DeviceDef, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("device-${device.code}")
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val rings = DeviceBody.previewRings(device)
        if (rings.any { it.size >= 2 }) SectionSketch(rings, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(device.code, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (device.needsReview) {
                Text(tr("Em revisão"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ImportBlock(catalog: DeviceCatalog, onCatalog: (DeviceCatalog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    HorizontalDivider()
    GButton(tr("Importar biblioteca"), { open = !open }, outlined = true)
    if (!open) {
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        return
    }
    OutlinedTextField(
        text,
        { text = it },
        modifier = Modifier.fillMaxWidth().testTag("drainage-import"),
        label = { Text(tr("Importar biblioteca (JSON)"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        minLines = 3,
        maxLines = 6,
    )
    GButton(tr("Importar"), {
        try {
            val user = CatalogLoader.parse(text)
            onCatalog(catalog.merge(user))
            text = ""
            message = tr("A biblioteca do usuário foi incorporada ({0} dispositivos).", user.devices.size)
            open = false
        } catch (error: CatalogFormatException) {
            message = when (error.kind) {
                CatalogFormatException.Kind.SCHEMA -> tr("Esquema {0} não é suportado.", error.detail.ifBlank { "?" })
                else -> tr("O JSON da biblioteca não pôde ser lido.")
            }
        }
    }, outlined = true)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun DeviceDetail(
    catalog: DeviceCatalog,
    device: DeviceDef,
    isPro: Boolean,
    onPro: () -> Unit,
    onBack: () -> Unit,
    onCatalog: (DeviceCatalog) -> Unit,
    onPlace: (DeviceCatalog, DeviceDef) -> Unit,
) {
    GButton(tr("Voltar"), onBack, outlined = true)
    Text(device.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(device.name, maxLines = 6, overflow = TextOverflow.Ellipsis)
    Text(
        "${chapterTitle(catalog, device.chapter)} · ${categoryLabel(device.category)} · ${device.family}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    device.position?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    Text(tr("Origem: {0}", device.origin), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (device.needsReview) {
        Text(tr("Em revisão"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("Lançamento e cálculos bloqueados até a revisão."), maxLines = 3, overflow = TextOverflow.Ellipsis)
        for (note in device.reviewNotes) {
            if (note.issue.isNotBlank()) {
                Text(tr("Problema: {0}", note.issue), style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        ReviewForm(catalog, device, onCatalog)
    }
    device.review?.let { Text(tr("Revisado por {0} em {1}.", it.by, it.date), maxLines = 3, overflow = TextOverflow.Ellipsis) }
    val section = device.section
    val drawn = DeviceBody.previewRings(device).ifEmpty { section?.rings.orEmpty() }
    if (drawn.none { it.size >= 2 }) Text(tr("Sem seção paramétrica"))
    else {
        SectionSketch(drawn, Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = tr("Seção do dispositivo") })
        section?.areaM2?.let { area ->
            val caption = section.excavationM3PerM?.let { cut ->
                tr("Área da seção: {0} m² ({1} m³/m)", formatNumber(area), formatNumber(cut))
            } ?: tr("Área da seção: {0} m²", formatNumber(area))
            Text(caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    device.variantsNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
    device.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
    if (device.parameters.isNotEmpty()) {
        Text(tr("Parâmetros"), style = MaterialTheme.typography.titleSmall)
        for (parameter in device.parameters) {
            Text("${parameter.key}: ${parameterText(parameter)}", maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    if (device.materials.isNotEmpty()) {
        Text(tr("Materiais"), style = MaterialTheme.typography.titleSmall)
        Text(device.materials.joinToString(", ") { it.name }, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
    Text(tr("Consumos"), style = MaterialTheme.typography.titleSmall)
    if (device.quantities.items.isEmpty()) Text(tr("Sem consumos tabelados."))
    QuantityList(device.quantities)
    CalculateRow(device, isPro, onPro)
    if (device.connections.isNotEmpty() || device.adaptableTo.isNotEmpty()) {
        Text(tr("Conexões"), style = MaterialTheme.typography.titleSmall)
        for (point in device.connections) ConnectionLine(point)
        if (device.adaptableTo.isNotEmpty()) {
            Text(device.adaptableTo.joinToString(", "), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
    Text(tr("Fonte"), style = MaterialTheme.typography.titleSmall)
    Text(
        tr("Fonte: {0}, página {1}, desenho {2}", device.page.source, device.page.pdfPage, device.page.drawing),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    catalog.sources[device.page.source]?.title?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
    PlaceRow(catalog, device, isPro, onPro, onPlace)
}

@Composable
private fun ReviewForm(catalog: DeviceCatalog, device: DeviceDef, onCatalog: (DeviceCatalog) -> Unit) {
    var reviewer by remember(device.code) { mutableStateOf("") }
    var date by remember(device.code) { mutableStateOf("") }
    var invalid by remember(device.code) { mutableStateOf(false) }
    OutlinedTextField(
        reviewer,
        { reviewer = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Revisado por"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
    )
    OutlinedTextField(
        date,
        { date = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(tr("Data"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
    )
    GButton(tr("Marcar como revisado"), {
        invalid = device.reviewed(reviewer, date) == null
        if (!invalid) onCatalog(catalog.withReview(device.code, reviewer, date))
    }, outlined = true)
    if (invalid) Text(tr("Informe o nome e a data."), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CalculateRow(device: DeviceDef, isPro: Boolean, onPro: () -> Unit) {
    var amount by remember(device.code) { mutableStateOf("1") }
    var notice by remember(device.code, device.review) { mutableStateOf<String?>(null) }
    var result by remember(device.code, device.review) { mutableStateOf<Quantities?>(null) }
    val label = if (device.category == DeviceCategory.POINT) tr("Unidades") else tr("Comprimento (m)")
    OutlinedTextField(
        amount,
        { amount = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
    )
    GButton(tr("Calcular"), enabled = device.calculable, onClick = {
        val factor = StationFormat.number(amount)
        when {
            factor == null || factor < 0.0 -> notice = tr("Informe um número.")
            device.access(isPro) == DeviceAccess.NEEDS_PRO -> onPro()
            device.access(isPro) == DeviceAccess.OPEN -> {
                result = device.quantitiesFor(factor, true)
                notice = if (device.category == DeviceCategory.POINT) tr("Para {0} un", formatNumber(factor)) else tr("Para {0} m", formatNumber(factor))
            }
            else -> Unit
        }
    }, outlined = true)
    notice?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    result?.let { QuantityList(it) }
}

@Composable
private fun PlaceRow(
    catalog: DeviceCatalog,
    device: DeviceDef,
    isPro: Boolean,
    onPro: () -> Unit,
    onPlace: (DeviceCatalog, DeviceDef) -> Unit,
) {
    GButton(tr("Lançar"), enabled = device.placeable, onClick = {
        when (device.access(isPro)) {
            DeviceAccess.OPEN -> onPlace(catalog, device)
            DeviceAccess.NEEDS_PRO -> onPro()
            DeviceAccess.NEEDS_REVIEW -> Unit
        }
    })
}

@Composable
private fun QuantityList(quantities: Quantities) {
    val groups = quantities.items.groupBy { it.method }
    for ((method, items) in groups) {
        if (method != null) Text(methodLabel(method), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        for (item in items) {
            Text("${item.name.replace('_', ' ')}: ${formatNumber(item.value)} ${item.unit}", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ConnectionLine(point: ConnectionPoint) {
    Text(roleLabel(point.role), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(point.accepts.joinToString(", "), maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SectionSketch(rings: List<List<SectionPoint>>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val points = rings.flatten()
        if (points.isEmpty()) return@Canvas
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val pad = minOf(8.dp.toPx(), size.minDimension * 0.12f)
        val spanX = (maxX - minX).takeIf { it > 1e-6 } ?: 1.0
        val spanY = (maxY - minY).takeIf { it > 1e-6 } ?: 1.0
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        fun at(point: SectionPoint) = Offset(
            (pad + (point.x - minX) * scale).toFloat(),
            (size.height - pad - (point.y - minY) * scale).toFloat(),
        )
        for (ring in rings) {
            if (ring.size < 2) continue
            val path = Path()
            val first = at(ring.first())
            path.moveTo(first.x, first.y)
            for (point in ring.drop(1)) {
                val next = at(point)
                path.lineTo(next.x, next.y)
            }
            path.close()
            drawPath(path, color.copy(alpha = 0.22f))
            drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

@Composable
private fun ChoiceRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    GButton(label, onClick, primary = selected, outlined = !selected)
}

private fun chapterTitle(catalog: DeviceCatalog, n: Int): String {
    val title = when (val name = catalog.chapters[n]) {
        "Drenagem superficial" -> tr("Drenagem superficial")
        "Drenos profundos" -> tr("Drenos profundos")
        "Drenos subsuperficiais" -> tr("Drenos subsuperficiais")
        "Drenos sub-horizontais" -> tr("Drenos sub-horizontais")
        "Drenagem urbana" -> tr("Drenagem urbana")
        "Bueiros tubulares" -> tr("Bueiros tubulares")
        "Bueiros celulares" -> tr("Bueiros celulares")
        "Minitúneis" -> tr("Minitúneis")
        "Canaletas com grelha" -> tr("Canaletas com grelha")
        null -> return tr("Capítulo {0}", n)
        else -> name
    }
    return "$n · $title"
}

private fun categoryLabel(category: DeviceCategory): String = when (category) {
    DeviceCategory.LINEAR -> tr("Linear")
    DeviceCategory.POINT -> tr("Pontual")
    DeviceCategory.OTHER -> tr("Outro")
}

private fun roleLabel(role: String): String = when (role) {
    "montante" -> tr("Montante")
    "jusante" -> tr("Jusante")
    "corpo" -> tr("Corpo")
    "ala" -> tr("Ala")
    else -> role
}

private fun methodLabel(method: String): String = when (method) {
    "convencional" -> tr("Convencional")
    "extrusao" -> tr("Extrusão")
    "pre_moldado" -> tr("Pré-moldado")
    "linha_simples" -> tr("Linha simples")
    else -> method.replace('_', ' ')
}

private fun parameterText(parameter: Parameter): String {
    val number = parameter.number
    val text = parameter.text
    return when {
        number != null -> formatNumber(number)
        !text.isNullOrBlank() -> text
        else -> tr("sem valor")
    }
}

private fun formatNumber(value: Double): String {
    val whole = abs(value - value.roundToInt()) < 1e-6 && abs(value) < 1_000_000
    if (whole) return StationFormat.decimal(value, 0)
    val text = StationFormat.decimal(value, 4)
    val separator = if (Display.portuguese) ',' else '.'
    return text.trimEnd('0').trimEnd(separator)
}

package main

import (
	"bufio"
	"bytes"
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// parsedSilverFragment is the neutral structural unit shared by deterministic
// Silver processors. StructuralOnly marks a parent retained for provenance and
// retrieval while more precise children carry the content for knowledge extraction.
type parsedSilverFragment struct {
	Kind           string
	Selector       map[string]any
	Excerpt        string
	Payload        any
	Text           string
	Context        *parsedSilverContext
	StructuralOnly bool
}

type parsedSilverContext struct {
	ID       string
	Kind     string
	Selector map[string]any
	Payload  any
}

type silverParseResult struct {
	Fragments []parsedSilverFragment
	Coverage  silverCoverage
}

func parseSilverReader(item bronzeItem, reader io.Reader) (silverParseResult, error) {
	if !declaredSilverText(item.Mime) && knownBinarySilverInput(item) {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	buffered := bufio.NewReaderSize(reader, silverInspectionBytes)
	prefix, err := buffered.Peek(silverInspectionBytes)
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, bufio.ErrBufferFull) {
		return silverParseResult{}, err
	}
	if !declaredSilverText(item.Mime) && silverPrefixLooksBinary(prefix) {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	data, err := io.ReadAll(io.LimitReader(buffered, silverMaximumInputBytes+1))
	if err != nil {
		return silverParseResult{}, err
	}
	if len(data) > silverMaximumInputBytes {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipSourceTooLarge,
		}}, nil
	}
	fragments, supported, err := parseSilverText(item, data)
	if err != nil {
		return silverParseResult{}, err
	}
	if !supported {
		return silverParseResult{Coverage: silverCoverage{
			ExtractionState: silverExtractionSkipped, SemanticState: silverSemanticSkipped,
			SemanticSkipReason: silverSkipUnsupportedContent,
		}}, nil
	}
	return silverParseResult{
		Fragments: fragments,
		Coverage:  silverCoverage{ExtractionState: silverExtractionCompleted},
	}, nil
}

func declaredSilverText(mime string) bool {
	mime = normalizedSilverMime(mime)
	return strings.HasPrefix(mime, "text/") || mime == "application/json" || mime == "application/csv"
}

func normalizedSilverMime(mime string) string {
	return strings.ToLower(strings.TrimSpace(strings.SplitN(mime, ";", 2)[0]))
}

func knownBinarySilverInput(item bronzeItem) bool {
	mime := normalizedSilverMime(item.Mime)
	if strings.HasPrefix(mime, "image/") || strings.HasPrefix(mime, "audio/") ||
		strings.HasPrefix(mime, "video/") || strings.HasPrefix(mime, "font/") {
		return true
	}
	switch mime {
	case "application/pdf", "application/zip", "application/gzip", "application/x-gzip",
		"application/x-7z-compressed", "application/x-rar-compressed":
		return true
	}
	lowerName := strings.ToLower(item.Title)
	for _, extension := range []string{".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".pdf", ".zip", ".gz", ".7z", ".rar", ".mp3", ".wav", ".flac", ".mp4", ".mov", ".avi", ".mkv"} {
		if strings.HasSuffix(lowerName, extension) {
			return true
		}
	}
	return false
}

func silverPrefixLooksBinary(data []byte) bool {
	if bytes.IndexByte(data, 0) >= 0 {
		return true
	}
	valid := data
	if !utf8.Valid(valid) {
		valid = nil
		for trim := 1; trim < utf8.UTFMax && trim < len(data); trim++ {
			candidate := data[:len(data)-trim]
			if utf8.Valid(candidate) {
				valid = candidate
				break
			}
		}
		if valid == nil {
			return true
		}
	}
	control := 0
	for _, r := range string(valid) {
		if r < 0x20 && r != '\n' && r != '\r' && r != '\t' {
			control++
		}
	}
	return len(valid) > 0 && control*100 > len(valid)
}

func parseSilverText(item bronzeItem, data []byte) ([]parsedSilverFragment, bool, error) {
	if len(data) == 0 {
		return []parsedSilverFragment{}, true, nil
	}
	byteOffset := 0
	if bytes.HasPrefix(data, []byte{0xef, 0xbb, 0xbf}) {
		data = data[3:]
		byteOffset = 3
	}
	declaredText := declaredSilverText(item.Mime)
	if !utf8.Valid(data) || bytes.IndexByte(data, 0) >= 0 {
		if declaredText {
			return nil, true, permanentSilverProcessError(errors.New("text-like Bronze is not valid UTF-8"))
		}
		return nil, false, nil
	}
	control := 0
	for _, r := range string(data) {
		if r < 0x20 && r != '\n' && r != '\r' && r != '\t' {
			control++
		}
	}
	if !declaredText && control*100 > len(data) {
		return nil, false, nil
	}
	text := string(data)
	lowerName := strings.ToLower(item.Title)
	mime := normalizedSilverMime(item.Mime)
	if mime == "application/json" || strings.HasSuffix(lowerName, ".json") {
		if fragments, ok := parseJSONFragments(text); ok {
			return fragments, true, nil
		}
	}
	if mime == "text/csv" || mime == "application/csv" || strings.HasSuffix(lowerName, ".csv") {
		if fragments, ok := parseCSVFragments(text); ok {
			return fragments, true, nil
		}
	}
	if mime == "text/markdown" || strings.HasSuffix(lowerName, ".md") || strings.HasSuffix(lowerName, ".markdown") {
		if fragments := parseMarkdownFragments(text); len(fragments) > 0 {
			return offsetSilverFragments(fragments, byteOffset), true, nil
		}
	}
	return offsetSilverFragments(parseGenericFragments(text), byteOffset), true, nil
}

func offsetSilverFragments(fragments []parsedSilverFragment, byteOffset int) []parsedSilverFragment {
	if byteOffset == 0 {
		return fragments
	}
	for index := range fragments {
		if start, ok := fragments[index].Selector["start_byte"].(int); ok {
			fragments[index].Selector["start_byte"] = start + byteOffset
		}
		if end, ok := fragments[index].Selector["end_byte"].(int); ok {
			fragments[index].Selector["end_byte"] = end + byteOffset
		}
	}
	return fragments
}

func parseJSONFragments(text string) ([]parsedSilverFragment, bool) {
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil {
		return nil, false
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, false
	}
	var fragments []parsedSilverFragment
	var visit func(any, string, *parsedSilverContext)
	visit = func(node any, path string, context *parsedSilverContext) {
		switch typed := node.(type) {
		case map[string]any:
			keys := make([]string, 0, len(typed))
			for key := range typed {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				childPath := path + "/" + jsonPointerEscape(key)
				visit(typed[key], childPath, jsonObjectContext(typed, keys, path, key))
			}
		case []any:
			for index, child := range typed {
				visit(child, path+"/"+strconv.Itoa(index), context)
			}
		default:
			encoded, _ := json.Marshal(typed)
			fragment := parsedSilverFragment{
				Kind: "parsed-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path},
				Excerpt: path + ": " + string(encoded), Payload: map[string]any{"path": path, "value": typed},
				Text: scalarText(typed), Context: context,
			}
			if value, ok := typed.(string); ok {
				if children, structured := structuredChildren(value, map[string]any{"kind": "json-string-child", "pointer": path}, context); structured {
					fragment.StructuralOnly = true
					fragments = append(fragments, fragment)
					fragments = append(fragments, children...)
					return
				}
			}
			fragments = append(fragments, fragment)
		}
	}
	visit(value, "", nil)
	return fragments, true
}

func parseCSVFragments(text string) ([]parsedSilverFragment, bool) {
	records, ok := parseCSVRecords(text)
	if !ok || len(records) == 0 {
		return nil, false
	}
	headers := records[0].Values
	fragments := make([]parsedSilverFragment, 0, len(records)*2)
	fragments = append(fragments, parsedSilverFragment{
		Kind: "parsed-table-header", Selector: map[string]any{"kind": "table-row", "row": 1, "source_line": records[0].SourceLine},
		Excerpt: strings.Join(headers, ", "), Payload: map[string]any{"row": 1, "values": headers},
	})
	for index, csvRecord := range records[1:] {
		record := csvRecord.Values
		row := index + 2
		payload := map[string]any{"row": row, "values": record}
		if len(headers) == len(record) {
			columns := map[string]string{}
			unique := true
			for column, header := range headers {
				if header == "" {
					unique = false
					break
				}
				if _, exists := columns[header]; exists {
					unique = false
					break
				}
				columns[header] = record[column]
			}
			if unique {
				payload["columns"] = columns
			}
		}
		fragments = append(fragments, parsedSilverFragment{
			Kind: "parsed-table-row", Selector: map[string]any{"kind": "table-row", "row": row, "source_line": csvRecord.SourceLine},
			Excerpt: strings.Join(record, ", "), Payload: payload, Text: strings.Join(record, " "), StructuralOnly: true,
		})
		for columnIndex, value := range record {
			if strings.TrimSpace(value) == "" {
				continue
			}
			header := ""
			if columnIndex < len(headers) {
				header = headers[columnIndex]
			}
			selector := map[string]any{
				"kind": "table-cell", "row": row, "column_index": columnIndex + 1,
			}
			if header != "" {
				selector["column"] = header
			}
			if columnIndex < len(csvRecord.Positions) {
				selector["source_line"] = csvRecord.Positions[columnIndex].Line
				selector["source_column"] = csvRecord.Positions[columnIndex].Column
			}
			context := csvRecordContext(row, headers, record, columnIndex)
			if children, structured := structuredChildren(value, selector, context); structured {
				fragments = append(fragments, children...)
				continue
			}
			fragments = append(fragments, parsedSilverFragment{
				Kind: "parsed-table-cell", Selector: selector,
				Excerpt: tableCellExcerpt(header, columnIndex, value),
				Payload: map[string]any{"row": row, "column": header, "column_index": columnIndex + 1, "value": value},
				Text:    value, Context: context,
			})
		}
	}
	return fragments, true
}

type csvSilverFieldPosition struct {
	Line   int
	Column int
}

type csvSilverRecord struct {
	Values     []string
	Positions  []csvSilverFieldPosition
	SourceLine int
}

// parseCSVRecords delegates RFC 4180 details, including escaped quotes and
// quoted newlines, to Go's standard library. Logical row numbers remain stable
// while FieldPos adds physical source locations for more precise selectors.
func parseCSVRecords(text string) ([]csvSilverRecord, bool) {
	reader := csv.NewReader(strings.NewReader(text))
	reader.FieldsPerRecord = 0
	var records []csvSilverRecord
	for {
		values, err := reader.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, false
		}
		record := csvSilverRecord{Values: append([]string(nil), values...)}
		for index := range values {
			line, column := reader.FieldPos(index)
			if index == 0 {
				record.SourceLine = line
			}
			record.Positions = append(record.Positions, csvSilverFieldPosition{Line: line, Column: column})
		}
		records = append(records, record)
	}
	return records, true
}

type structuredScalarUnit struct {
	Kind     string
	Selector map[string]any
	Payload  any
	Text     string
	Excerpt  string
	Start    int
	End      int
}

func jsonPointerEscape(value string) string {
	return strings.ReplaceAll(strings.ReplaceAll(value, "~", "~0"), "/", "~1")
}

func newParsedSilverContext(kind string, selector map[string]any, payload any) *parsedSilverContext {
	context := &parsedSilverContext{Kind: kind, Selector: selector, Payload: payload}
	context.ID = stableID("source-silver-parent-context", struct {
		Kind     string         `json:"kind"`
		Selector map[string]any `json:"selector"`
		Payload  any            `json:"payload"`
	}{kind, selector, payload})
	return context
}

func boundedContextString(value string) string {
	return truncate(value, silverMaximumContextRunes)
}

func contextStringFits(value string) bool {
	return len(value) <= silverMaximumContextRunes*utf8.UTFMax &&
		utf8.RuneCountInString(value) <= silverMaximumContextRunes
}

func jsonObjectContext(object map[string]any, keys []string, path, excludedKey string) *parsedSilverContext {
	fields := make([]map[string]any, 0, min(len(keys), silverMaximumContextFields))
	inspected := 0
	for _, key := range keys {
		if key == excludedKey {
			continue
		}
		inspected++
		if inspected > silverMaximumContextCandidates {
			break
		}
		if len(fields) >= silverMaximumContextFields {
			break
		}
		value := object[key]
		switch typed := value.(type) {
		case string:
			if !contextStringFits(typed) {
				continue
			}
			if _, structured := decomposeStructuredString(typed, 0); structured {
				continue
			}
			value = boundedContextString(typed)
		case json.Number, bool:
		default:
			continue
		}
		fields = append(fields, map[string]any{
			"name": key, "path": path + "/" + jsonPointerEscape(key), "value": value,
		})
	}
	if len(fields) == 0 {
		return nil
	}
	selector := map[string]any{"kind": "json-object-context", "pointer": path, "excluded_key": excludedKey}
	return newParsedSilverContext("json-object", selector, map[string]any{"path": path, "fields": fields})
}

func csvRecordContext(row int, headers, values []string, excludedColumn int) *parsedSilverContext {
	fields := make([]map[string]any, 0, min(len(values), silverMaximumContextFields))
	inspected := 0
	for columnIndex, value := range values {
		if columnIndex == excludedColumn || strings.TrimSpace(value) == "" {
			continue
		}
		inspected++
		if inspected > silverMaximumContextCandidates {
			break
		}
		if !contextStringFits(value) {
			continue
		}
		if _, structured := decomposeStructuredString(value, 0); structured {
			continue
		}
		if len(fields) >= silverMaximumContextFields {
			break
		}
		header := ""
		if columnIndex < len(headers) {
			header = headers[columnIndex]
		}
		fields = append(fields, map[string]any{
			"column": header, "column_index": columnIndex + 1, "value": boundedContextString(value),
		})
	}
	if len(fields) == 0 {
		return nil
	}
	selector := map[string]any{"kind": "table-record-context", "row": row, "excluded_column_index": excludedColumn + 1}
	return newParsedSilverContext("table-record", selector, map[string]any{"row": row, "fields": fields})
}

func tableCellExcerpt(header string, columnIndex int, value string) string {
	label := header
	if label == "" {
		label = fmt.Sprintf("column %d", columnIndex+1)
	}
	return truncate(label+": "+value, 240)
}

func structuredChildren(value string, baseSelector map[string]any, context *parsedSilverContext) ([]parsedSilverFragment, bool) {
	units, ok := decomposeStructuredString(value, 0)
	if !ok {
		return nil, false
	}
	fragments := make([]parsedSilverFragment, 0, len(units))
	for index, unit := range units {
		selector := make(map[string]any, len(baseSelector)+2)
		for key, item := range baseSelector {
			selector[key] = item
		}
		if selector["kind"] == "table-cell" {
			selector["kind"] = "table-cell-child"
		}
		selector["child"] = index + 1
		selector["structure"] = unit.Selector
		fragments = append(fragments, parsedSilverFragment{
			Kind: unit.Kind, Selector: selector, Excerpt: truncate(unit.Excerpt, 240),
			Payload: unit.Payload, Text: unit.Text, Context: context,
		})
	}
	return fragments, true
}

func decomposeStructuredString(value string, depth int) ([]structuredScalarUnit, bool) {
	if depth >= silverMaximumStructureDepth {
		return nil, false
	}
	trimmed := strings.TrimSpace(value)
	if len(trimmed) > 1 && (trimmed[0] == '{' || trimmed[0] == '[') {
		decoder := json.NewDecoder(strings.NewReader(trimmed))
		decoder.UseNumber()
		var decoded any
		if decoder.Decode(&decoded) == nil && decoder.Decode(&struct{}{}) == io.EOF {
			units := embeddedJSONUnits(decoded, "", depth+1)
			if len(units) > 0 {
				return units, true
			}
		}
	}
	return keyValueLineUnits(value, depth)
}

func embeddedJSONUnits(value any, path string, depth int) []structuredScalarUnit {
	var units []structuredScalarUnit
	switch typed := value.(type) {
	case map[string]any:
		keys := make([]string, 0, len(typed))
		for key := range typed {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			units = append(units, embeddedJSONUnits(typed[key], path+"/"+jsonPointerEscape(key), depth)...)
		}
	case []any:
		for index, child := range typed {
			units = append(units, embeddedJSONUnits(child, path+"/"+strconv.Itoa(index), depth)...)
		}
	default:
		if text, ok := typed.(string); ok {
			if children, structured := decomposeStructuredString(text, depth); structured {
				for _, child := range children {
					child.Selector = map[string]any{"kind": "json-value-child", "pointer": path, "structure": child.Selector}
					child.Payload = map[string]any{"path": path, "value": child.Payload}
					units = append(units, child)
				}
				return units
			}
		}
		encoded, _ := json.Marshal(typed)
		units = append(units, structuredScalarUnit{
			Kind: "parsed-embedded-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path},
			Payload: map[string]any{"path": path, "value": typed}, Text: scalarText(typed),
			Excerpt: path + ": " + string(encoded),
		})
	}
	return units
}

func keyValueLineUnits(value string, depth int) ([]structuredScalarUnit, bool) {
	type parsedLine struct {
		key, value, text string
		start, end       int
	}
	var lines []parsedLine
	offset := 0
	for _, withNewline := range strings.SplitAfter(value, "\n") {
		line := strings.TrimSuffix(withNewline, "\n")
		line = strings.TrimSuffix(line, "\r")
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			colon := strings.IndexByte(trimmed, ':')
			if colon <= 0 {
				return nil, false
			}
			key := strings.TrimSpace(trimmed[:colon])
			item := strings.TrimSpace(trimmed[colon+1:])
			if !validStructuredKey(key) || item == "" {
				return nil, false
			}
			start := offset + strings.Index(line, trimmed)
			lines = append(lines, parsedLine{key: key, value: item, text: trimmed, start: start, end: start + len(trimmed)})
		}
		offset += len(withNewline)
	}
	if len(lines) < 2 {
		return nil, false
	}
	units := make([]structuredScalarUnit, 0, len(lines))
	for _, line := range lines {
		if children, structured := decomposeStructuredString(line.value, depth+1); structured {
			for _, child := range children {
				child.Selector = map[string]any{"kind": "key-value-child", "key": line.key, "structure": child.Selector}
				child.Payload = map[string]any{"key": line.key, "value": child.Payload}
				child.Text = line.key + ": " + child.Text
				child.Excerpt = line.key + ": " + child.Excerpt
				child.Start, child.End = line.start, line.end
				units = append(units, child)
			}
			continue
		}
		units = append(units, structuredScalarUnit{
			Kind: "parsed-key-value", Selector: map[string]any{"kind": "key-value", "key": line.key},
			Payload: map[string]any{"key": line.key, "value": line.value}, Text: line.text,
			Excerpt: line.text, Start: line.start, End: line.end,
		})
	}
	return units, true
}

func validStructuredKey(value string) bool {
	if value == "" || len([]rune(value)) > 120 {
		return false
	}
	hasLetterOrDigit := false
	for _, character := range value {
		if unicode.IsLetter(character) || unicode.IsDigit(character) {
			hasLetterOrDigit = true
			continue
		}
		switch character {
		case ' ', '\t', '-', '_', '.', '/', '(', ')', '#':
		default:
			return false
		}
	}
	return hasLetterOrDigit
}

func parseMarkdownFragments(text string) []parsedSilverFragment {
	var fragments []parsedSilverFragment
	offset := 0
	paragraphStart := -1
	var paragraph []string
	flush := func(end int) {
		if len(paragraph) == 0 {
			return
		}
		start, trimmedEnd, value := trimmedSilverRange(text, paragraphStart, end)
		fragment := fragmentWithTextRange("text-block", start, trimmedEnd, value, map[string]any{"text": value})
		fragments = append(fragments, expandStructuredTextFragment(fragment)...)
		paragraph = nil
		paragraphStart = -1
	}
	for _, lineWithNewline := range strings.SplitAfter(text, "\n") {
		line := strings.TrimSuffix(strings.TrimSuffix(lineWithNewline, "\n"), "\r")
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "#") {
			prefix := len(trimmed) - len(strings.TrimLeft(trimmed, "#"))
			if prefix <= 6 && len(trimmed) > prefix && trimmed[prefix] == ' ' {
				flush(offset)
				value := strings.TrimSpace(trimmed[prefix:])
				lineStart := offset + strings.Index(line, value)
				fragments = append(fragments, fragmentWithTextRange("markdown-heading", lineStart, lineStart+len(value), value, map[string]any{"level": prefix, "text": value}))
				offset += len(lineWithNewline)
				continue
			}
		}
		if trimmed == "" {
			flush(offset)
			offset += len(lineWithNewline)
			continue
		}
		if paragraphStart < 0 {
			paragraphStart = offset
		}
		paragraph = append(paragraph, line)
		offset += len(lineWithNewline)
	}
	flush(len(text))
	return splitLargeFragments(fragments)
}

func parseGenericFragments(text string) []parsedSilverFragment {
	var fragments []parsedSilverFragment
	start := 0
	for start < len(text) {
		end := strings.Index(text[start:], "\n\n")
		if end < 0 {
			end = len(text)
		} else {
			end = start + end
		}
		trimmedStart, trimmedEnd, value := trimmedSilverRange(text, start, end)
		if value != "" {
			fragment := fragmentWithTextRange("text-block", trimmedStart, trimmedEnd, value, map[string]any{"text": value})
			fragments = append(fragments, expandStructuredTextFragment(fragment)...)
		}
		if end == len(text) {
			break
		}
		start = end + 2
	}
	return splitLargeFragments(fragments)
}

func expandStructuredTextFragment(fragment parsedSilverFragment) []parsedSilverFragment {
	units, ok := decomposeStructuredString(fragment.Text, 0)
	if !ok {
		return []parsedSilverFragment{fragment}
	}
	fragment.StructuralOnly = true
	fragments := []parsedSilverFragment{fragment}
	start, _ := fragment.Selector["start_byte"].(int)
	end, _ := fragment.Selector["end_byte"].(int)
	for index, unit := range units {
		selector := map[string]any{"kind": "text-block-child", "start_byte": start, "end_byte": end, "child": index + 1, "structure": unit.Selector}
		if unit.End > unit.Start {
			selector["kind"] = "utf8-byte-range"
			selector["start_byte"] = start + unit.Start
			selector["end_byte"] = start + unit.End
		}
		fragments = append(fragments, parsedSilverFragment{
			Kind: unit.Kind, Selector: selector, Excerpt: truncate(unit.Excerpt, 240),
			Payload: unit.Payload, Text: unit.Text,
		})
	}
	return fragments
}

func trimmedSilverRange(text string, start, end int) (int, int, string) {
	value := text[start:end]
	trimmedLeft := strings.TrimLeftFunc(value, unicode.IsSpace)
	trimmed := strings.TrimRightFunc(trimmedLeft, unicode.IsSpace)
	trimmedStart := start + len(value) - len(trimmedLeft)
	return trimmedStart, trimmedStart + len(trimmed), trimmed
}

func splitLargeFragments(input []parsedSilverFragment) []parsedSilverFragment {
	var output []parsedSilverFragment
	for _, fragment := range input {
		if len(fragment.Text) <= silverMaximumBatchBytes ||
			(fragment.Kind != "text-block" && fragment.Kind != "markdown-heading") {
			output = append(output, fragment)
			continue
		}
		base, _ := fragment.Selector["start_byte"].(int)
		remaining := fragment.Text
		consumed := 0
		for len(remaining) > 0 {
			end := min(len(remaining), silverMaximumBatchBytes)
			for end < len(remaining) && end > 0 && !utf8.RuneStart(remaining[end]) {
				end--
			}
			if end == 0 {
				_, size := utf8.DecodeRuneInString(remaining)
				end = size
			}
			part := remaining[:end]
			partFragment := fragmentWithTextRange(fragment.Kind, base+consumed, base+consumed+len(part), part, map[string]any{"text": part})
			partFragment.Context = fragment.Context
			partFragment.StructuralOnly = fragment.StructuralOnly
			output = append(output, partFragment)
			remaining = remaining[end:]
			consumed += end
		}
	}
	return output
}

func fragmentWithTextRange(kind string, start, end int, text string, payload any) parsedSilverFragment {
	return parsedSilverFragment{Kind: kind, Selector: map[string]any{"kind": "utf8-byte-range", "start_byte": start, "end_byte": end}, Excerpt: truncate(text, 240), Payload: payload, Text: text}
}

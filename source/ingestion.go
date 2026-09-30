package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// parsedSilverFragment is the small, neutral structural unit shared by
// deterministic Silver processors. Text is natural-language content when the
// unit has any; structural-only units such as table headers leave it empty.
type parsedSilverFragment struct {
	Kind     string
	Selector map[string]any
	Excerpt  string
	Payload  any
	Text     string
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
		if fragments[index].Selector["kind"] != "utf8-byte-range" {
			continue
		}
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
	var visit func(any, string)
	visit = func(node any, path string) {
		switch typed := node.(type) {
		case map[string]any:
			keys := make([]string, 0, len(typed))
			for key := range typed {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			for _, key := range keys {
				visit(typed[key], path+"/"+strings.ReplaceAll(strings.ReplaceAll(key, "~", "~0"), "/", "~1"))
			}
		case []any:
			for index, child := range typed {
				visit(child, path+"/"+strconv.Itoa(index))
			}
		default:
			encoded, _ := json.Marshal(typed)
			fragments = append(fragments, parsedSilverFragment{Kind: "parsed-json-value", Selector: map[string]any{"kind": "json-pointer", "pointer": path}, Excerpt: path + ": " + string(encoded), Payload: map[string]any{"path": path, "value": typed}, Text: scalarText(typed)})
		}
	}
	visit(value, "")
	return fragments, true
}

func parseCSVFragments(text string) ([]parsedSilverFragment, bool) {
	records, ok := parseCSVRecords(text)
	if !ok || len(records) == 0 {
		return nil, false
	}
	headers := records[0]
	fragments := make([]parsedSilverFragment, 0, len(records))
	fragments = append(fragments, parsedSilverFragment{
		Kind: "parsed-table-header", Selector: map[string]any{"kind": "table-row", "row": 1},
		Excerpt: strings.Join(headers, ", "), Payload: map[string]any{"row": 1, "values": headers},
	})
	for index, record := range records[1:] {
		payload := map[string]any{"row": index + 2, "values": record}
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
		fragments = append(fragments, parsedSilverFragment{Kind: "parsed-table-row", Selector: map[string]any{"kind": "table-row", "row": index + 2}, Excerpt: strings.Join(record, ", "), Payload: payload, Text: strings.Join(record, " ")})
	}
	return fragments, true
}

// parseCSVRecords is a small RFC 4180 reader used here to keep deterministic
// ingestion dependency-free. It handles quoted fields, escaped quotes, CRLF
// and newlines inside quoted fields. Malformed CSV falls back to generic text.
func parseCSVRecords(text string) ([][]string, bool) {
	var records [][]string
	var record []string
	var field strings.Builder
	inQuotes := false
	quoted := false
	for index := 0; index < len(text); index++ {
		character := text[index]
		if inQuotes {
			if character == '"' {
				if index+1 < len(text) && text[index+1] == '"' {
					field.WriteByte('"')
					index++
				} else {
					inQuotes = false
					quoted = true
				}
			} else {
				field.WriteByte(character)
			}
			continue
		}
		switch character {
		case '"':
			if field.Len() != 0 || quoted {
				return nil, false
			}
			inQuotes = true
		case ',':
			record = append(record, field.String())
			field.Reset()
			quoted = false
		case '\n':
			record = append(record, strings.TrimSuffix(field.String(), "\r"))
			field.Reset()
			quoted = false
			records = append(records, record)
			record = nil
		default:
			if quoted && character != '\r' {
				return nil, false
			}
			field.WriteByte(character)
		}
	}
	if inQuotes {
		return nil, false
	}
	if field.Len() > 0 || quoted || len(record) > 0 {
		record = append(record, strings.TrimSuffix(field.String(), "\r"))
		records = append(records, record)
	}
	return records, true
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
		fragments = append(fragments, fragmentWithTextRange("text-block", start, trimmedEnd, value, map[string]any{"text": value}))
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
			fragments = append(fragments, fragmentWithTextRange("text-block", trimmedStart, trimmedEnd, value, map[string]any{"text": value}))
		}
		if end == len(text) {
			break
		}
		start = end + 2
	}
	return splitLargeFragments(fragments)
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
		if len(fragment.Text) <= silverMaximumBatchBytes {
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
			output = append(output, fragmentWithTextRange(fragment.Kind, base+consumed, base+consumed+len(part), part, map[string]any{"text": part}))
			remaining = remaining[end:]
			consumed += end
		}
	}
	return output
}

func fragmentWithTextRange(kind string, start, end int, text string, payload any) parsedSilverFragment {
	return parsedSilverFragment{Kind: kind, Selector: map[string]any{"kind": "utf8-byte-range", "start_byte": start, "end_byte": end}, Excerpt: truncate(text, 240), Payload: payload, Text: text}
}

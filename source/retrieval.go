package main

import (
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	_ "github.com/asg017/sqlite-vec-go-bindings/ncruces"
	_ "github.com/ncruces/go-sqlite3/driver"
)

const (
	retrievalSchemaVersion             = 1
	retrievalChunkProcessorID          = "source.silver.retrieval-chunks"
	retrievalChunkProcessorVersion     = "1"
	retrievalEmbeddingProcessorID      = "source.silver.embeddings"
	retrievalEmbeddingProcessorVersion = "1"
	retrievalDefaultLimit              = 10
	retrievalMaximumLimit              = 50
	retrievalEmbeddingBatchSize        = 32
	retrievalFusionConstant            = 60.0
	retrievalReconcileInterval         = 10 * time.Second
)

type retrievalService struct {
	db       *sql.DB
	path     string
	silver   *silverService
	embedder Embedder
	wake     chan struct{}
	mu       sync.Mutex
	status   retrievalStatus
}

type retrievalStatus struct {
	State             string         `json:"state"`
	Error             string         `json:"error,omitempty"`
	Chunks            int            `json:"chunks"`
	Embeddings        int            `json:"embeddings"`
	ChunkProcessor    silverProducer `json:"chunk_processor"`
	EmbeddingProducer silverProducer `json:"embedding_processor"`
}

type retrievalChunk struct {
	ID                  string
	BronzeSourceID      string
	BronzeContentSHA256 string
	Title               string
	Mime                string
	EvidenceID          string
	Selector            map[string]any
	Text                string
	EvidenceProducer    silverProducer
}

type retrievalRequest struct {
	Query string `json:"query"`
	Limit int    `json:"limit,omitempty"`
}

type retrievalResponse struct {
	Query          string            `json:"query"`
	Results        []retrievalResult `json:"results"`
	LexicalCount   int               `json:"lexical_count"`
	SemanticCount  int               `json:"semantic_count"`
	SemanticState  string            `json:"semantic_state"`
	SemanticError  string            `json:"semantic_error,omitempty"`
	Representation retrievalStatus   `json:"representation"`
}

type retrievalResult struct {
	ChunkID             string         `json:"chunk_id"`
	Text                string         `json:"text"`
	Score               float64        `json:"score"`
	LexicalRank         int            `json:"lexical_rank,omitempty"`
	LexicalScore        float64        `json:"lexical_score,omitempty"`
	SemanticRank        int            `json:"semantic_rank,omitempty"`
	SemanticScore       float64        `json:"semantic_score,omitempty"`
	SemanticDistance    *float64       `json:"semantic_distance,omitempty"`
	BronzeSourceID      string         `json:"bronze_source_id"`
	BronzeContentSHA256 string         `json:"bronze_content_sha256"`
	BronzeTitle         string         `json:"bronze_title"`
	BronzeMime          string         `json:"bronze_mime"`
	EvidenceID          string         `json:"evidence_id"`
	EvidenceSelector    map[string]any `json:"evidence_selector,omitempty"`
	EvidenceProducer    silverProducer `json:"evidence_producer"`
	ChunkProcessor      silverProducer `json:"chunk_processor"`
	EmbeddingProcessor  silverProducer `json:"embedding_processor,omitempty"`
}

type rankedRetrievalRow struct {
	rowID    int64
	chunkID  string
	distance float64
	rawScore float64
}

func newRetrievalService(dir string, silver *silverService, embedder Embedder) (*retrievalService, error) {
	if silver == nil {
		return nil, errors.New("retrieval requires Silver")
	}
	if embedder != nil && embedder.Dimensions() <= 0 {
		return nil, errors.New("retrieval embedder dimensions must be positive")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, "retrieval.sqlite")
	dsn := (&url.URL{Scheme: "file", Path: path}).String()
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	service := &retrievalService{db: db, path: path, silver: silver, embedder: embedder, wake: make(chan struct{}, 1)}
	if err := service.initialize(); err != nil {
		db.Close()
		return nil, err
	}
	status, err := service.readStatus()
	if err != nil {
		db.Close()
		return nil, err
	}
	service.status = status
	_ = os.Chmod(path, 0600)
	return service, nil
}

func (s *retrievalService) initialize() error {
	statements := []string{
		`PRAGMA foreign_keys = ON`,
		`PRAGMA journal_mode = WAL`,
		`PRAGMA synchronous = FULL`,
		`PRAGMA busy_timeout = 5000`,
		`CREATE TABLE IF NOT EXISTS retrieval_schema (version INTEGER NOT NULL)`,
		`INSERT INTO retrieval_schema(version) SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM retrieval_schema)`,
		`CREATE TABLE IF NOT EXISTS retrieval_sources (
            bronze_source_id TEXT PRIMARY KEY,
            bronze_content_sha256 TEXT NOT NULL,
            title TEXT NOT NULL,
            mime TEXT NOT NULL,
            processor_id TEXT NOT NULL,
            processor_version TEXT NOT NULL,
            chunk_count INTEGER NOT NULL,
            chunk_set_sha256 TEXT NOT NULL,
            indexed_at INTEGER NOT NULL
        )`,
		`CREATE TABLE IF NOT EXISTS retrieval_chunks (
            rowid INTEGER PRIMARY KEY AUTOINCREMENT,
            chunk_id TEXT NOT NULL UNIQUE,
            bronze_source_id TEXT NOT NULL REFERENCES retrieval_sources(bronze_source_id) ON DELETE CASCADE,
            bronze_content_sha256 TEXT NOT NULL,
            title TEXT NOT NULL,
            mime TEXT NOT NULL,
            evidence_id TEXT NOT NULL,
            selector_json TEXT NOT NULL,
            text TEXT NOT NULL,
            evidence_processor_id TEXT NOT NULL,
            evidence_processor_version TEXT NOT NULL,
            UNIQUE(bronze_source_id, evidence_id)
        )`,
		`CREATE VIRTUAL TABLE IF NOT EXISTS retrieval_fts USING fts5(chunk_id UNINDEXED, text, tokenize='unicode61')`,
		`CREATE TABLE IF NOT EXISTS retrieval_embedding_rows (
            chunk_rowid INTEGER PRIMARY KEY REFERENCES retrieval_chunks(rowid) ON DELETE CASCADE
        )`,
		`CREATE TABLE IF NOT EXISTS retrieval_representation (
            kind TEXT PRIMARY KEY,
            processor_id TEXT NOT NULL,
            processor_version TEXT NOT NULL,
            model_id TEXT NOT NULL,
            model_revision TEXT NOT NULL,
            dimensions INTEGER NOT NULL,
            state TEXT NOT NULL,
            error TEXT NOT NULL,
            updated_at INTEGER NOT NULL
        )`,
	}
	for _, statement := range statements {
		if _, err := s.db.Exec(statement); err != nil {
			return fmt.Errorf("initialize retrieval SQLite: %w", err)
		}
	}
	var version int
	if err := s.db.QueryRow(`SELECT version FROM retrieval_schema`).Scan(&version); err != nil {
		return err
	}
	if version != retrievalSchemaVersion {
		return fmt.Errorf("unsupported retrieval schema version %d", version)
	}
	var vecVersion string
	if err := s.db.QueryRow(`SELECT vec_version()`).Scan(&vecVersion); err != nil || vecVersion == "" {
		return fmt.Errorf("sqlite-vec unavailable: %w", err)
	}
	return nil
}

func (s *retrievalService) close() error { return s.db.Close() }

func (s *retrievalService) start(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(retrievalReconcileInterval)
		defer ticker.Stop()
		for {
			if err := s.reconcile(ctx); err != nil && !errors.Is(err, context.Canceled) {
				s.setRuntimeError(err)
			}
			select {
			case <-ctx.Done():
				return
			case <-s.wake:
			case <-ticker.C:
			}
		}
	}()
	s.signal()
}

func (s *retrievalService) signal() {
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

func (s *retrievalService) setRuntimeError(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.status.State = "failed"
	s.status.Error = err.Error()
}

func (s *retrievalService) currentStatus() retrievalStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.status
}

func (s *retrievalService) reconcile(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	snapshot := s.silver.snapshot()
	chunksBySource, err := retrievalChunksFromSnapshot(snapshot)
	if err != nil {
		return err
	}
	changed, err := s.syncChunks(ctx, snapshot.Sources, chunksBySource)
	if err != nil {
		return err
	}
	if err := s.syncEmbeddings(ctx, changed); err != nil {
		return err
	}
	status, err := s.readStatus()
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.status = status
	s.mu.Unlock()
	return nil
}

func retrievalChunksFromSnapshot(snapshot silverSnapshot) (map[string][]retrievalChunk, error) {
	sources := make(map[string]silverSource, len(snapshot.Sources))
	for _, source := range snapshot.Sources {
		sources[source.BronzeSourceID] = source
	}
	evidence := make(map[string]silverEvidence, len(snapshot.Evidence))
	for _, value := range snapshot.Evidence {
		evidence[value.ID] = value
	}
	result := make(map[string][]retrievalChunk, len(snapshot.Sources))
	for _, observation := range snapshot.Observations {
		if observation.Producer.ProcessorID != silverExtractionID || len(observation.EvidenceIDs) != 1 {
			continue
		}
		text, ok := retrievalText(observation.Kind, observation.Payload)
		if !ok || strings.TrimSpace(text) == "" {
			continue
		}
		item, ok := evidence[observation.EvidenceIDs[0]]
		if !ok {
			return nil, fmt.Errorf("retrieval observation %s refers to missing evidence", observation.ID)
		}
		source, ok := sources[item.BronzeSourceID]
		if !ok || source.BronzeContentSHA256 != item.BronzeContentSHA256 {
			continue
		}
		chunk := retrievalChunk{
			BronzeSourceID: item.BronzeSourceID, BronzeContentSHA256: item.BronzeContentSHA256,
			Title: source.Title, Mime: source.Mime, EvidenceID: item.ID, Selector: item.Selector,
			Text: text, EvidenceProducer: observation.Producer,
		}
		chunk.ID = stableID("source-silver-retrieval-chunk", struct {
			EvidenceID, ProcessorID, ProcessorVersion string
		}{item.ID, retrievalChunkProcessorID, retrievalChunkProcessorVersion})
		result[source.BronzeSourceID] = append(result[source.BronzeSourceID], chunk)
	}
	for sourceID := range result {
		sort.Slice(result[sourceID], func(i, j int) bool { return result[sourceID][i].ID < result[sourceID][j].ID })
	}
	return result, nil
}

func retrievalText(kind string, payload json.RawMessage) (string, bool) {
	switch kind {
	case "text-block", "markdown-heading":
		var value struct {
			Text string `json:"text"`
		}
		if json.Unmarshal(payload, &value) != nil {
			return "", false
		}
		return value.Text, true
	case "parsed-json-value":
		var value struct {
			Path  string `json:"path"`
			Value any    `json:"value"`
		}
		if json.Unmarshal(payload, &value) != nil {
			return "", false
		}
		text := scalarText(value.Value)
		if value.Path != "" {
			text = value.Path + ": " + text
		}
		return text, true
	case "parsed-table-header", "parsed-table-row":
		var value struct {
			Values  []string          `json:"values"`
			Columns map[string]string `json:"columns"`
		}
		if json.Unmarshal(payload, &value) != nil {
			return "", false
		}
		if len(value.Columns) > 0 {
			keys := make([]string, 0, len(value.Columns))
			for key := range value.Columns {
				keys = append(keys, key)
			}
			sort.Strings(keys)
			parts := make([]string, 0, len(keys))
			for _, key := range keys {
				parts = append(parts, key+": "+value.Columns[key])
			}
			return strings.Join(parts, "; "), true
		}
		return strings.Join(value.Values, ", "), true
	default:
		return "", false
	}
}

func (s *retrievalService) syncChunks(ctx context.Context, sources []silverSource, chunks map[string][]retrievalChunk) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	wanted := make(map[string]silverSource, len(sources))
	for _, source := range sources {
		wanted[source.BronzeSourceID] = source
	}
	stored := map[string]struct {
		hash, processorID, processorVersion, chunkSetSHA256 string
		chunkCount                                          int
	}{}
	rows, err := tx.QueryContext(ctx, `SELECT bronze_source_id, bronze_content_sha256, processor_id, processor_version,
        chunk_count, chunk_set_sha256 FROM retrieval_sources`)
	if err != nil {
		return false, err
	}
	for rows.Next() {
		var id, hash, processorID, processorVersion, chunkSetSHA256 string
		var chunkCount int
		if err := rows.Scan(&id, &hash, &processorID, &processorVersion, &chunkCount, &chunkSetSHA256); err != nil {
			rows.Close()
			return false, err
		}
		stored[id] = struct {
			hash, processorID, processorVersion, chunkSetSHA256 string
			chunkCount                                          int
		}{hash, processorID, processorVersion, chunkSetSHA256, chunkCount}
	}
	if err := rows.Close(); err != nil {
		return false, err
	}
	changed := false
	for id, existing := range stored {
		source, exists := wanted[id]
		expectedChunks := chunks[id]
		if exists && existing.hash == source.BronzeContentSHA256 &&
			existing.processorID == retrievalChunkProcessorID && existing.processorVersion == retrievalChunkProcessorVersion &&
			existing.chunkCount == len(expectedChunks) && existing.chunkSetSHA256 == retrievalChunkSetSHA256(expectedChunks) {
			continue
		}
		if err := deleteRetrievalSource(ctx, tx, id); err != nil {
			return false, err
		}
		changed = true
	}
	for _, source := range sources {
		expectedChunks := chunks[source.BronzeSourceID]
		if existing, exists := stored[source.BronzeSourceID]; exists && existing.hash == source.BronzeContentSHA256 &&
			existing.processorID == retrievalChunkProcessorID && existing.processorVersion == retrievalChunkProcessorVersion &&
			existing.chunkCount == len(expectedChunks) && existing.chunkSetSHA256 == retrievalChunkSetSHA256(expectedChunks) {
			continue
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO retrieval_sources(
            bronze_source_id, bronze_content_sha256, title, mime, processor_id, processor_version,
            chunk_count, chunk_set_sha256, indexed_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`, source.BronzeSourceID, source.BronzeContentSHA256, source.Title, source.Mime,
			retrievalChunkProcessorID, retrievalChunkProcessorVersion, len(expectedChunks),
			retrievalChunkSetSHA256(expectedChunks), time.Now().UnixMilli()); err != nil {
			return false, err
		}
		for _, chunk := range expectedChunks {
			selector, err := json.Marshal(chunk.Selector)
			if err != nil {
				return false, err
			}
			result, err := tx.ExecContext(ctx, `INSERT INTO retrieval_chunks(
                chunk_id, bronze_source_id, bronze_content_sha256, title, mime, evidence_id, selector_json, text,
                evidence_processor_id, evidence_processor_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, chunk.ID, chunk.BronzeSourceID, chunk.BronzeContentSHA256,
				chunk.Title, chunk.Mime, chunk.EvidenceID, string(selector), chunk.Text,
				chunk.EvidenceProducer.ProcessorID, chunk.EvidenceProducer.ProcessorVersion)
			if err != nil {
				return false, err
			}
			rowID, err := result.LastInsertId()
			if err != nil {
				return false, err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO retrieval_fts(rowid, chunk_id, text) VALUES (?, ?, ?)`, rowID, chunk.ID, chunk.Text); err != nil {
				return false, err
			}
		}
		changed = true
	}
	if changed {
		if _, err := tx.ExecContext(ctx, `UPDATE retrieval_representation SET state = 'rebuilding', error = '', updated_at = ? WHERE kind = 'embeddings'`, time.Now().UnixMilli()); err != nil {
			return false, err
		}
	}
	if err := tx.Commit(); err != nil {
		return false, err
	}
	return changed, nil
}

func retrievalChunkSetSHA256(chunks []retrievalChunk) string {
	type identity struct {
		ID                       string         `json:"id"`
		EvidenceID               string         `json:"evidence_id"`
		Selector                 map[string]any `json:"selector"`
		Text                     string         `json:"text"`
		EvidenceProcessorID      string         `json:"evidence_processor_id"`
		EvidenceProcessorVersion string         `json:"evidence_processor_version"`
	}
	values := make([]identity, 0, len(chunks))
	for _, chunk := range chunks {
		values = append(values, identity{
			ID: chunk.ID, EvidenceID: chunk.EvidenceID, Selector: chunk.Selector, Text: chunk.Text,
			EvidenceProcessorID:      chunk.EvidenceProducer.ProcessorID,
			EvidenceProcessorVersion: chunk.EvidenceProducer.ProcessorVersion,
		})
	}
	return stableID("source-silver-retrieval-chunk-set", values)
}

func deleteRetrievalSource(ctx context.Context, tx *sql.Tx, id string) error {
	rows, err := tx.QueryContext(ctx, `SELECT rowid FROM retrieval_chunks WHERE bronze_source_id = ?`, id)
	if err != nil {
		return err
	}
	var rowIDs []int64
	for rows.Next() {
		var rowID int64
		if err := rows.Scan(&rowID); err != nil {
			rows.Close()
			return err
		}
		rowIDs = append(rowIDs, rowID)
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, rowID := range rowIDs {
		if _, err := tx.ExecContext(ctx, `DELETE FROM retrieval_fts WHERE rowid = ?`, rowID); err != nil {
			return err
		}
		if retrievalVectorTableExists(ctx, tx) {
			if _, err := tx.ExecContext(ctx, `DELETE FROM retrieval_vec WHERE rowid = ?`, rowID); err != nil {
				return err
			}
		}
	}
	_, err = tx.ExecContext(ctx, `DELETE FROM retrieval_sources WHERE bronze_source_id = ?`, id)
	return err
}

type queryRower interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func retrievalVectorTableExists(ctx context.Context, database queryRower) bool {
	var count int
	err := database.QueryRowContext(ctx, `SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'retrieval_vec'`).Scan(&count)
	return err == nil && count == 1
}

func (s *retrievalService) syncEmbeddings(ctx context.Context, chunksChanged bool) error {
	if s.embedder == nil {
		_, err := s.db.ExecContext(ctx, `INSERT INTO retrieval_representation(
            kind, processor_id, processor_version, model_id, model_revision, dimensions, state, error, updated_at
        ) VALUES ('embeddings', ?, ?, '', '', 0, 'unavailable', 'embedding model is not configured', ?)
        ON CONFLICT(kind) DO UPDATE SET processor_id=excluded.processor_id, processor_version=excluded.processor_version,
            model_id='', model_revision='', dimensions=0, state='unavailable', error=excluded.error, updated_at=excluded.updated_at`,
			retrievalEmbeddingProcessorID, retrievalEmbeddingProcessorVersion, time.Now().UnixMilli())
		return err
	}
	identity := s.embedder.Identity()
	var processorID, processorVersion, modelID, modelRevision, state string
	var dimensions int
	err := s.db.QueryRowContext(ctx, `SELECT processor_id, processor_version, model_id, model_revision, dimensions, state
        FROM retrieval_representation WHERE kind = 'embeddings'`).Scan(
		&processorID, &processorVersion, &modelID, &modelRevision, &dimensions, &state,
	)
	mismatch := errors.Is(err, sql.ErrNoRows) || processorID != retrievalEmbeddingProcessorID ||
		processorVersion != retrievalEmbeddingProcessorVersion || modelID != identity.ID ||
		modelRevision != identity.Revision || dimensions != s.embedder.Dimensions()
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if mismatch {
		if err := s.rebuildVectorTable(ctx, identity); err != nil {
			return err
		}
	} else if chunksChanged || state != "ready" {
		if _, err := s.db.ExecContext(ctx, `UPDATE retrieval_representation SET state='rebuilding', error='', updated_at=? WHERE kind='embeddings'`, time.Now().UnixMilli()); err != nil {
			return err
		}
	}

	for {
		rows, err := s.db.QueryContext(ctx, `SELECT c.rowid, c.text FROM retrieval_chunks c
            LEFT JOIN retrieval_embedding_rows e ON e.chunk_rowid = c.rowid
            WHERE e.chunk_rowid IS NULL ORDER BY c.rowid LIMIT ?`, retrievalEmbeddingBatchSize)
		if err != nil {
			return err
		}
		var ids []int64
		var texts []string
		for rows.Next() {
			var id int64
			var text string
			if err := rows.Scan(&id, &text); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, id)
			texts = append(texts, text)
		}
		if err := rows.Close(); err != nil {
			return err
		}
		if len(ids) == 0 {
			break
		}
		vectors, err := s.embedder.Embed(ctx, texts)
		if err != nil {
			_ = s.setEmbeddingFailure(ctx, err)
			return fmt.Errorf("build retrieval embeddings: %w", err)
		}
		if len(vectors) != len(ids) {
			err := errors.New("embedder returned the wrong number of vectors")
			_ = s.setEmbeddingFailure(ctx, err)
			return err
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		for index, vector := range vectors {
			if len(vector) != s.embedder.Dimensions() {
				tx.Rollback()
				err := fmt.Errorf("embedder returned vector with %d dimensions", len(vector))
				_ = s.setEmbeddingFailure(ctx, err)
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO retrieval_vec(rowid, embedding) VALUES (?, ?)`, ids[index], serializeFloat32(vector)); err != nil {
				tx.Rollback()
				return err
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO retrieval_embedding_rows(chunk_rowid) VALUES (?)`, ids[index]); err != nil {
				tx.Rollback()
				return err
			}
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	_, err = s.db.ExecContext(ctx, `UPDATE retrieval_representation SET state='ready', error='', updated_at=? WHERE kind='embeddings'`, time.Now().UnixMilli())
	return err
}

func (s *retrievalService) rebuildVectorTable(ctx context.Context, identity ModelIdentity) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DROP TABLE IF EXISTS retrieval_vec`); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, fmt.Sprintf(
		`CREATE VIRTUAL TABLE retrieval_vec USING vec0(embedding float[%d] distance_metric=cosine)`, s.embedder.Dimensions())); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM retrieval_embedding_rows`); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO retrieval_representation(
        kind, processor_id, processor_version, model_id, model_revision, dimensions, state, error, updated_at
    ) VALUES ('embeddings', ?, ?, ?, ?, ?, 'rebuilding', '', ?)
    ON CONFLICT(kind) DO UPDATE SET processor_id=excluded.processor_id, processor_version=excluded.processor_version,
        model_id=excluded.model_id, model_revision=excluded.model_revision, dimensions=excluded.dimensions,
        state=excluded.state, error='', updated_at=excluded.updated_at`, retrievalEmbeddingProcessorID,
		retrievalEmbeddingProcessorVersion, identity.ID, identity.Revision, s.embedder.Dimensions(), time.Now().UnixMilli())
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (s *retrievalService) setEmbeddingFailure(ctx context.Context, failure error) error {
	_, err := s.db.ExecContext(ctx, `UPDATE retrieval_representation SET state='failed', error=?, updated_at=? WHERE kind='embeddings'`,
		truncate(failure.Error(), 1000), time.Now().UnixMilli())
	return err
}

func serializeFloat32(vector []float32) []byte {
	value := make([]byte, len(vector)*4)
	for index, component := range vector {
		binary.LittleEndian.PutUint32(value[index*4:], math.Float32bits(component))
	}
	return value
}

func (s *retrievalService) readStatus() (retrievalStatus, error) {
	status := retrievalStatus{ChunkProcessor: silverProducer{
		ProcessorID: retrievalChunkProcessorID, ProcessorVersion: retrievalChunkProcessorVersion,
	}, EmbeddingProducer: silverProducer{ProcessorID: retrievalEmbeddingProcessorID, ProcessorVersion: retrievalEmbeddingProcessorVersion}}
	if err := s.db.QueryRow(`SELECT count(*) FROM retrieval_chunks`).Scan(&status.Chunks); err != nil {
		return status, err
	}
	if err := s.db.QueryRow(`SELECT count(*) FROM retrieval_embedding_rows`).Scan(&status.Embeddings); err != nil {
		return status, err
	}
	var modelID, modelRevision string
	err := s.db.QueryRow(`SELECT state, error, model_id, model_revision FROM retrieval_representation WHERE kind='embeddings'`).Scan(
		&status.State, &status.Error, &modelID, &modelRevision,
	)
	if errors.Is(err, sql.ErrNoRows) {
		status.State = "unavailable"
		status.Error = "embedding representation is not initialized"
		return status, nil
	}
	if err != nil {
		return status, err
	}
	status.EmbeddingProducer.ModelID = modelID
	status.EmbeddingProducer.ModelRevision = modelRevision
	return status, nil
}

func (s *retrievalService) search(ctx context.Context, request retrievalRequest) (retrievalResponse, error) {
	request.Query = strings.TrimSpace(request.Query)
	if request.Query == "" || len(request.Query) > 16*1024 {
		return retrievalResponse{}, errors.New("retrieval query must contain 1-16384 bytes")
	}
	if request.Limit == 0 {
		request.Limit = retrievalDefaultLimit
	}
	if request.Limit < 1 || request.Limit > retrievalMaximumLimit {
		return retrievalResponse{}, fmt.Errorf("retrieval limit must be between 1 and %d", retrievalMaximumLimit)
	}
	candidateLimit := min(retrievalMaximumLimit, max(request.Limit*4, request.Limit))
	lexical, err := s.lexicalSearch(ctx, request.Query, candidateLimit)
	if err != nil {
		return retrievalResponse{}, err
	}
	status, err := s.readStatus()
	if err != nil {
		return retrievalResponse{}, err
	}
	var semantic []rankedRetrievalRow
	semanticError := ""
	if s.embedder == nil {
		semanticError = "embedding model is not configured"
	} else if status.State != "ready" {
		semanticError = status.Error
		if semanticError == "" {
			semanticError = "embedding representation is " + status.State
		}
	} else {
		vectors, embedErr := s.embedder.Embed(ctx, []string{request.Query})
		if embedErr != nil {
			semanticError = embedErr.Error()
		} else if len(vectors) != 1 || len(vectors[0]) != s.embedder.Dimensions() {
			semanticError = "embedding model returned an invalid query vector"
		} else {
			semantic, err = s.vectorSearch(ctx, vectors[0], candidateLimit)
			if err != nil {
				semanticError = err.Error()
			}
		}
	}
	results, err := s.fuseResults(ctx, lexical, semantic, request.Limit, status.EmbeddingProducer)
	if err != nil {
		return retrievalResponse{}, err
	}
	return retrievalResponse{
		Query: request.Query, Results: results, LexicalCount: len(lexical), SemanticCount: len(semantic),
		SemanticState: status.State, SemanticError: semanticError, Representation: status,
	}, nil
}

func (s *retrievalService) lexicalSearch(ctx context.Context, query string, limit int) ([]rankedRetrievalRow, error) {
	match := lexicalFTSQuery(query)
	if match == "" {
		return []rankedRetrievalRow{}, nil
	}
	rows, err := s.db.QueryContext(ctx, `SELECT c.rowid, c.chunk_id, bm25(retrieval_fts)
        FROM retrieval_fts JOIN retrieval_chunks c ON c.rowid = retrieval_fts.rowid
        WHERE retrieval_fts MATCH ? ORDER BY bm25(retrieval_fts), c.chunk_id LIMIT ?`, match, limit)
	if err != nil {
		return nil, fmt.Errorf("lexical retrieval: %w", err)
	}
	defer rows.Close()
	result := []rankedRetrievalRow{}
	for rows.Next() {
		var value rankedRetrievalRow
		if err := rows.Scan(&value.rowID, &value.chunkID, &value.rawScore); err != nil {
			return nil, err
		}
		result = append(result, value)
	}
	return result, rows.Err()
}

func lexicalFTSQuery(query string) string {
	fields := strings.Fields(query)
	parts := make([]string, 0, len(fields))
	seen := map[string]bool{}
	for _, field := range fields {
		field = strings.TrimSpace(field)
		if field == "" || seen[field] {
			continue
		}
		seen[field] = true
		parts = append(parts, `"`+strings.ReplaceAll(field, `"`, `""`)+`"`)
	}
	return strings.Join(parts, " OR ")
}

func (s *retrievalService) vectorSearch(ctx context.Context, vector []float32, limit int) ([]rankedRetrievalRow, error) {
	if len(vector) == 0 {
		return nil, errors.New("query embedding is empty")
	}
	rows, err := s.db.QueryContext(ctx, `SELECT c.rowid, c.chunk_id, v.distance
		FROM (SELECT rowid, distance FROM retrieval_vec WHERE embedding MATCH ? AND k = ?) v
		JOIN retrieval_chunks c ON c.rowid = v.rowid
		ORDER BY v.distance, c.chunk_id`, serializeFloat32(vector), limit)
	if err != nil {
		return nil, fmt.Errorf("vector retrieval: %w", err)
	}
	defer rows.Close()
	result := []rankedRetrievalRow{}
	for rows.Next() {
		var value rankedRetrievalRow
		if err := rows.Scan(&value.rowID, &value.chunkID, &value.distance); err != nil {
			return nil, err
		}
		result = append(result, value)
	}
	return result, rows.Err()
}

func (s *retrievalService) fuseResults(ctx context.Context, lexical, semantic []rankedRetrievalRow, limit int, embeddingProducer silverProducer) ([]retrievalResult, error) {
	byRow := map[int64]*retrievalResult{}
	for rank, candidate := range lexical {
		value := byRow[candidate.rowID]
		if value == nil {
			value = &retrievalResult{ChunkID: candidate.chunkID}
			byRow[candidate.rowID] = value
		}
		value.LexicalRank = rank + 1
		value.LexicalScore = 1 / (retrievalFusionConstant + float64(rank+1))
		value.Score += value.LexicalScore
	}
	for rank, candidate := range semantic {
		value := byRow[candidate.rowID]
		if value == nil {
			value = &retrievalResult{ChunkID: candidate.chunkID}
			byRow[candidate.rowID] = value
		}
		value.SemanticRank = rank + 1
		value.SemanticScore = 1 / (retrievalFusionConstant + float64(rank+1))
		value.Score += value.SemanticScore
		distance := candidate.distance
		value.SemanticDistance = &distance
		value.EmbeddingProcessor = embeddingProducer
	}
	results := make([]retrievalResult, 0, len(byRow))
	for rowID, value := range byRow {
		var selector string
		err := s.db.QueryRowContext(ctx, `SELECT text, bronze_source_id, bronze_content_sha256, title, mime,
            evidence_id, selector_json, evidence_processor_id, evidence_processor_version
            FROM retrieval_chunks WHERE rowid = ?`, rowID).Scan(&value.Text, &value.BronzeSourceID,
			&value.BronzeContentSHA256, &value.BronzeTitle, &value.BronzeMime, &value.EvidenceID,
			&selector, &value.EvidenceProducer.ProcessorID, &value.EvidenceProducer.ProcessorVersion)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal([]byte(selector), &value.EvidenceSelector); err != nil {
			return nil, err
		}
		value.ChunkProcessor = silverProducer{ProcessorID: retrievalChunkProcessorID, ProcessorVersion: retrievalChunkProcessorVersion}
		results = append(results, *value)
	}
	sort.Slice(results, func(i, j int) bool {
		if results[i].Score == results[j].Score {
			return results[i].ChunkID < results[j].ChunkID
		}
		return results[i].Score > results[j].Score
	})
	if len(results) > limit {
		results = results[:limit]
	}
	return results, nil
}

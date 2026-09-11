package database

import (
	"database/sql"
	"errors"
)

type LibraryItem struct {
	ID              string `json:"id"`
	ContentSHA256   string `json:"contentSha256"`
	EncryptedSHA256 string `json:"encryptedSha256,omitempty"`
	Bytes           int64  `json:"bytes,omitempty"`
	CreatedAt       int64  `json:"createdAt"`
	DeletedAt       *int64 `json:"deletedAt,omitempty"`
}

func (d *DB) FindLibraryItem(userID, itemID string) (*LibraryItem, error) {
	var item LibraryItem
	var encrypted sql.NullString
	var bytes sql.NullInt64
	err := d.sql.QueryRow(`SELECT item_id,content_sha256,encrypted_sha256,byte_count,created_at,deleted_at FROM library_items WHERE user_id=? AND item_id=?`, userID, itemID).
		Scan(&item.ID, &item.ContentSHA256, &encrypted, &bytes, &item.CreatedAt, &item.DeletedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	item.EncryptedSHA256 = encrypted.String
	item.Bytes = bytes.Int64
	return &item, nil
}

func (d *DB) FindActiveLibraryItemByContent(userID, contentSHA256 string) (*LibraryItem, error) {
	var item LibraryItem
	err := d.sql.QueryRow(`SELECT item_id,content_sha256,encrypted_sha256,byte_count,created_at,deleted_at FROM library_items WHERE user_id=? AND content_sha256=? AND deleted_at IS NULL`, userID, contentSHA256).
		Scan(&item.ID, &item.ContentSHA256, &item.EncryptedSHA256, &item.Bytes, &item.CreatedAt, &item.DeletedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &item, err
}

func (d *DB) InsertLibraryItem(userID string, item LibraryItem) error {
	_, err := d.sql.Exec(`INSERT INTO library_items(user_id,item_id,content_sha256,encrypted_sha256,byte_count,created_at) VALUES(?,?,?,?,?,?)`,
		userID, item.ID, item.ContentSHA256, item.EncryptedSHA256, item.Bytes, item.CreatedAt)
	return err
}

func (d *DB) PutLibraryTombstone(userID, itemID, contentSHA256 string, deletedAt int64) error {
	_, err := d.sql.Exec(`INSERT INTO library_items(user_id,item_id,content_sha256,created_at,deleted_at) VALUES(?,?,?,?,?)
ON CONFLICT(user_id,item_id) DO UPDATE SET
    encrypted_sha256=NULL,
    byte_count=NULL,
    deleted_at=MAX(COALESCE(library_items.deleted_at,0),excluded.deleted_at)`,
		userID, itemID, contentSHA256, deletedAt, deletedAt)
	return err
}

func (d *DB) TotalLibraryBytes(userID string) (int64, error) {
	var n int64
	err := d.sql.QueryRow(`SELECT COALESCE(SUM(byte_count),0) FROM library_items WHERE user_id=? AND deleted_at IS NULL`, userID).Scan(&n)
	return n, err
}

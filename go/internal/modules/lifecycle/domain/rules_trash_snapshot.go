package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"sort"
)

func NewTrashSnapshot(target Target, catalog, reaction string) *TrashSnapshot {
	value := &TrashSnapshot{digest: sha256.New()}
	if ValidateTarget(target.Type, target.ID) != nil || target.Type == "comic" || target.State != "active" {
		value.err = ErrValidation
	}
	value.add(struct {
		Version           int
		Target            Target
		Catalog, Reaction string
	}{1, target, catalog, reaction})
	return value
}

func (value *TrashSnapshot) AddMedia(fact TrashMediaFact) error {
	if value.members >= MaxTrashMembers || fact.ID <= value.lastID || !ValidJournalID(fact.ID) {
		value.err = ErrValidation
	}
	value.members++
	value.lastID = fact.ID
	value.add(fact)
	return value.err
}

func (value *TrashSnapshot) Seal(files []FileCandidate) (string, error) {
	if err := ValidateCandidates(files); err != nil {
		return "", err
	}
	ordered := append([]FileCandidate(nil), files...)
	sort.Slice(ordered, func(i, j int) bool { return ordered[i].Path < ordered[j].Path })
	value.add(struct{ Members, Files int }{value.members, len(ordered)})
	for _, file := range ordered {
		value.add(file)
	}
	if value.err != nil {
		return "", value.err
	}
	return hex.EncodeToString(value.digest.Sum(nil)), nil
}

func (value *TrashSnapshot) add(fact any) {
	if value.err != nil {
		return
	}
	data, err := json.Marshal(fact)
	if err != nil {
		value.err = err
		return
	}
	if len(data)+value.bytes+1 > MaxJournalBytes {
		value.err = ErrValidation
		return
	}
	value.bytes += len(data) + 1
	_, _ = value.digest.Write(data)
	_, _ = value.digest.Write([]byte{'\n'})
}

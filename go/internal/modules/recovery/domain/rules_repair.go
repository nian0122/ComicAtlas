package domain

import "math"

func ValidateRepairProof(run RepairRun, attempt int, hash string) error {
	if !Key(run.TaskID) || attempt < 1 || !validHash(hash) || run.Completed {
		return ErrValidation
	}
	return ValidateRepairRequest(run.ComicID, RepairRequest{Version: run.ExpectedVersion, StructureVersion: run.ExpectedStructureVersion})
}

func ValidateRepairRequest(comic string, request RepairRequest) error {
	if !Key(comic) || request.Version < 1 || request.StructureVersion < 1 || request.Version == math.MaxInt || request.StructureVersion == math.MaxInt {
		return ErrValidation
	}
	return nil
}

func ValidateRepairFacts(request RepairRequest, facts RepairFacts) error {
	if facts.Version != request.Version || facts.StructureVersion != request.StructureVersion || facts.HasStructure || facts.Busy {
		return ErrConflict
	}
	return nil
}

func RepairVersionPolicy(current RepairRequest, sourceVersion, sourceStructure int) (RepairPolicy, error) {
	version, structure := max(current.Version, sourceVersion), max(current.StructureVersion, sourceStructure)
	if version < 1 || structure < 1 || version == math.MaxInt || structure == math.MaxInt {
		return RepairPolicy{}, ErrLimit
	}
	return RepairPolicy{Version: version + 1, StructureVersion: structure + 1, PreserveMetadata: current.Version > 1}, nil
}

func ValidateRepairGeneration(run RepairRun, facts RepairFacts) error {
	if err := ValidateRepairFacts(RepairRequest{Version: run.ExpectedVersion, StructureVersion: run.ExpectedStructureVersion}, facts); err != nil {
		return err
	}
	if run.Completed || run.MarkerCreatedAt != facts.MarkerCreatedAt {
		return ErrConflict
	}
	if (run.MarkerTaskID == nil) != (facts.MarkerTaskID == nil) {
		return ErrConflict
	}
	if run.MarkerTaskID != nil && *run.MarkerTaskID != *facts.MarkerTaskID {
		return ErrConflict
	}
	return nil
}

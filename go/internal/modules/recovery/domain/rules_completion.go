package domain

func ValidateRecoveryCompletion(run DiscoveryRun, summary ResultSummary, attempt, completed int) error {
	if err := ValidateDiscoveryWrite(run); err != nil {
		return err
	}
	if !run.Sealed || run.Attempt != attempt || summary.Total != run.CandidateCount || summary.Processed != summary.Total || summary.Failed != 0 || completed != summary.Processed || summary.Restored+summary.Placeholders+summary.Skipped+summary.Empty != summary.Processed {
		return ErrConflict
	}
	return nil
}

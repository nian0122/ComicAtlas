package domain

type CommandProgress struct{ Total, Pending, Active, Completed, Cancelled int }
type CommandParent struct{ TaskID, PreviewID, Status, OptionsJSON string }
type ChildObservation struct {
	Position  int
	Status    string
	ErrorCode *string
}
type DispatchUpdate struct {
	Status                string
	ErrorCode, WaitReason *string
	NextTryAt             string
}

package domain

type ResourceTask struct {
	Type, TargetType, TargetID, ComicID, Title, Payload string
	SourcePath                                          string
	ReservationBatchID                                  string
	ReservationPosition                                 int
}

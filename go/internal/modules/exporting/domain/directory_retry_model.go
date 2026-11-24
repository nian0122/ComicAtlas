package domain

type DirectoryRetryState struct {
	TaskID, Type, Status, ComicID, TargetType, TargetID, Payload string
	PlanID                                                       string
	HasPlan                                                      bool
	Completed                                                    bool
	Members, DetachedMembers                                     int
}

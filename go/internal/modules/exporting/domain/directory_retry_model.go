package domain

type DirectoryRetryState struct {
	TaskID, Type, Status, ComicID, TargetType, TargetID, Payload string
	PreviewID, OwnerTaskID, PlanSHA256                           string
	Completed                                                    bool
	Members, DetachedMembers                                     int
}

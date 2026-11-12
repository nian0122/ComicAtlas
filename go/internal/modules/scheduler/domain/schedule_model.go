package domain

import "time"

type Settings struct {
	Enabled  bool
	Interval time.Duration
}

type Update struct {
	Enabled  *bool
	Interval *time.Duration
}

type Run struct {
	ID         string  `json:"id"`
	ScheduleID string  `json:"scheduleId"`
	Trigger    string  `json:"trigger"`
	StartedAt  string  `json:"startedAt"`
	FinishedAt *string `json:"finishedAt"`
	Processed  int     `json:"processed"`
	Error      *string `json:"error"`
}

type Schedule struct {
	ID              string  `json:"id"`
	Title           string  `json:"title"`
	Description     string  `json:"description"`
	Unit            string  `json:"unit"`
	IntervalSeconds int     `json:"intervalSeconds"`
	Enabled         bool    `json:"enabled"`
	Running         bool    `json:"running"`
	NextRunAt       *string `json:"nextRunAt"`
	LastRun         *Run    `json:"lastRun"`
	Runs            []Run   `json:"runs"`
}

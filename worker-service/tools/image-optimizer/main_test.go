package main

import "testing"

func TestInferPageNumber(t *testing.T) {
	cases := []struct {
		name string
		want int64
	}{
		{"001", 1},
		{"page_05", 5},
		{"IMG_6513", 6513},
		{"123", 123},
		{"no-number", 0},
		{"", 0},
		{"12-34", 34},
		{"001-1", 1},
	}
	for _, c := range cases {
		got := inferPageNumber(c.name)
		if got != c.want {
			t.Errorf("inferPageNumber(%q) = %d, want %d", c.name, got, c.want)
		}
	}
}

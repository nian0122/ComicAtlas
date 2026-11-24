package filesystem

import "context"

func (move *directoryExecution) nameReceipt(group *directoryGroup, phase string) []byte {
	return []byte(move.id + "\n" + group.target + "\n" + phase)
}

func (move *directoryExecution) writeNameReceipt(ctx context.Context, group *directoryGroup, phase string) error {
	if group.receipt == "" {
		return nil
	}
	if err := move.ensureControl(ctx, group.receipt+"."+phase, move.nameReceipt(group, phase)); err != nil {
		return err
	}
	group.phase = phase
	return nil
}

func (move *directoryExecution) readNameReceipts(ctx context.Context) error {
	for name, group := range move.receipts {
		for _, phase := range []string{"whole", "renaming"} {
			receipt := name + "." + phase
			info, err := move.entry(move.base + "/" + receipt)
			if err != nil {
				return err
			}
			if info == nil {
				break
			}
			if err = move.checkControl(ctx, receipt, move.nameReceipt(group, phase)); err != nil {
				return err
			}
			group.phase = phase
		}
	}
	return nil
}

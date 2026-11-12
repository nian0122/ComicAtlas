package domain

import "errors"

// 文案只来自已审阅的目录，永不从异常、路径或工具输出拼接。
var failures = map[string]Failure{
	FailureAI:                      {Message: "AI 分析未完成", Action: "检查样本、图片工具与存储后重试；候选不会自动写入资料"},
	FailureAITimeout:               {Message: "AI 分析超时", Action: "检查服务响应与主机负载后手动重试"},
	FailureAIUnavailable:           {Message: "AI 服务不可用", Action: "启动可选 AI 服务并检查连接后手动重试，普通任务可继续执行"},
	FailureAIBusy:                  {Message: "AI 服务繁忙", Action: "等待当前推理完成后手动重试"},
	FailureAIProtocol:              {Message: "AI 候选结果不符合协议", Action: "检查 AI 服务版本与结构化输出后手动重试"},
	FailureAISource:                {Message: "AI 来源与受理快照不一致", Action: "按当前资料重新提交分析；旧任务不会覆盖当前资料"},
	FailureRecovery:                {Message: "扫盘恢复未完成", Action: "检查来源文件、权限和数据库后重试原任务，已提交结果会保留"},
	FailureRecoveryPartial:         {Message: "部分漫画恢复失败", Action: "查看逐本错误并修复来源，再重试原任务；完整恢复、占位、跳过和空壳不会重复处理"},
	FailureRecoveryTimeout:         {Message: "扫盘恢复超时", Action: "检查存储与工具响应后重试原任务，沿封存候选继续未处理项"},
	FailureDirectoryMove:           {Message: "文件夹移出未完成", Action: "保留已移出目录、检查点及原任务；检查空间与权限后沿原任务重试，不要删除唯一副本"},
	FailureDirectoryPreflight:      {Message: "文件夹移出预检未完成", Action: "检查选中资料、来源文件与权限后重试预检；原件尚未移动"},
	FailureDirectoryMoveSource:     {Message: "移出文件与冻结测量不一致", Action: "保留现场，恢复对应来源或输出后重试原任务，不要重新建立计划"},
	FailureDirectoryMoveConflict:   {Message: "移出范围或目标目录发生冲突", Action: "保留检查点并排查冲突后重试原任务，不覆盖已有目录"},
	FailureDirectoryMoveTimeout:    {Message: "文件夹移出超时", Action: "检查存储响应后重试原任务，已完成移动会沿检查点恢复"},
	FailureCommandBatch:            {Message: "批量资源协调未完成", Action: "已受理子任务继续保留；检查逐项任务后重试父任务，只继续未分派项或收尾，不重复创建已关联任务"},
	FailureMetadataBatch:           {Message: "批量资料更新未完成", Action: "已提交的逐项修改保留；检查逐项结果，修复存储后重试原任务；版本冲突项应重新预览，取消不会撤销已完成项"},
	FailureMetadataSync:            {Message: "自动侧车同步未完成", Action: "资料已保存在数据库；检查元数据存储后重试，后台会合并新编辑并退避重试；侧车不是完整数据库备份"},
	FailureLifecycleAudit:          {Message: "生命周期文件核对未完成", Action: "检查任务范围与存储后重新核对；未完成的报告不能作为修复依据"},
	FailureLifecycle:               {Message: "生命周期文件处理尚未完成", Action: "检查清单、文件冲突和存储权限后重试原任务；保留检查点及唯一副本，永久清理开始后不能还原"},
	FailurePurgePreflight:          {Message: "永久清理预检失败，条目仍在回收站", Action: "在回收站核对条目及文件清单，确认后重新发起永久清理"},
	FailureTrashPreparation:        {Message: "回收准备失败，漫画已恢复到正常库", Action: "在漫画管理中核对媒体，处理问题后重新发起回收"},
	FailureMetadataRefresh:         {Message: "元数据刷新未完成", Action: "检查媒体、工具和侧车存储后重试；已生效的媒体更新不会撤销"},
	FailureMetadataRefreshTimeout:  {Message: "元数据刷新超时", Action: "检查文件规模与存储响应后重试"},
	FailureMetadataRefreshConflict: {Message: "刷新基线与当前资料或媒体不一致", Action: "检查并发编辑；未生效的任务可按当前资料重新提交，已生效任务需完成侧车发布"},
	FailureMetadataRefreshLimit:    {Message: "元数据刷新超过有界扫描限制", Action: "检查目录规模、深度和文件大小后重试"},
	FailureUpload:                  {Message: "上传发布未完成", Action: "检查暂存和目标存储后重试；未生效的失败会话可放弃后重新上传"},
	FailureUploadTimeout:           {Message: "上传发布超时", Action: "检查文件规模和存储后重试，已保存的检查点会复用"},
	FailureUploadChecksum:          {Message: "上传文件与清单摘要不一致", Action: "恢复清单对应文件后重试；或放弃未生效会话并重新上传"},
	FailureUploadMedia:             {Message: "上传文件不是有效的声明媒体", Action: "放弃尚未生效的失败会话，检查真实媒体后重新上传"},
	FailureUploadConflict:          {Message: "上传目标或原件已变化", Action: "检查目标状态；未生效的失败会话可放弃后重新上传"},
	FailureUploadRetire:            {Message: "上传已生效但旧件保留未完成", Action: "新媒体可能已可读；检查旧件和保留区后重试，不要删除唯一副本"},
	FailureTranscodeUnavailable:    {Message: "视频转码工具不可用", Action: "检查固定 ffmpeg、ffprobe 的配置、编码能力和执行权限后重试"},
	FailureTranscode:               {Message: "视频转码未完成", Action: "检查视频完整性、工具与存储后重试；已完成项保留，未切换项仍使用原件"},
	FailureTranscodeTimeout:        {Message: "视频转码超时", Action: "检查视频规模与主机负载后重试；原件保留"},
	FailureTranscodeLimit:          {Message: "视频超过转码处理限制", Action: "检查文件大小与画面尺寸；原件仍可读取"},
	FailureTranscodeSource:         {Message: "视频与转码快照不一致", Action: "检查原件及媒体关联后重试，保留暂存与检查点"},
	FailureTranscodeCleanup:        {Message: "视频转码完成项的旧件清理未完成", Action: "检查新副本及旧件权限后重试；不要删除唯一可读副本"},
	FailureExport:                  {Message: "归档导出未完成", Action: "检查导出存储的空间、权限和完整性后重试"},
	FailureExportSource:            {Message: "导出来源与受理快照不一致", Action: "恢复快照对应的原件或 LQ 后重试，或按当前资料新建导出"},
	FailureExportConflict:          {Message: "导出与媒体变更任务冲突", Action: "等待相关媒体任务完成后重试或新建导出"},
	FailureExportTimeout:           {Message: "归档导出超时", Action: "检查媒体数量及存储响应后重试"},
	FailureScanTimeout:             {Message: "导入候选扫描超时", Action: "检查来源读取速度，或缩小扫描范围后重试"},
	FailureHQRelease:               {Message: "HQ 释放未完成", Action: "检查原件、存储权限及保留的 LQ 后重试；不要手动删除释放检查点"},
	FailureHQPrerequisite:          {Message: "HQ 释放前提发生变化", Action: "检查原件关联及 LQ 完整性，恢复后重试；不要删除唯一可读副本"},
	FailureHQTimeout:               {Message: "HQ 释放超时", Action: "检查存储响应和文件规模后重试；已完成项会保留"},
	FailureStorageRead:             {Message: "存储占用扫描失败", Action: "检查媒体根目录的权限和文件变化后重试；上次完整统计仍保留"},
	FailureStorageLimit:            {Message: "存储占用扫描超过条目或深度限制", Action: "检查目录层级和条目数量；上次完整统计仍保留"},
	FailureStorageTimeout:          {Message: "存储占用扫描超时", Action: "检查存储设备响应和主机负载后重试；上次完整统计仍保留"},
	FailureImageToolUnavailable:    {Message: "图片处理工具不可用", Action: "检查部署中的图片工具及执行权限后重试"},
	FailureImageBudget:             {Message: "图片解码规模超过处理预算", Action: "检查图片尺寸或调整处理预算后重试"},
	FailureImageRender:             {Message: "图片派生失败", Action: "检查图片完整性和处理工具后重试"},
	FailureImageTimeout:            {Message: "图片派生超时", Action: "检查图片复杂度、处理预算和主机负载后重试"},
	FailureUnknown:                 {Message: "导入未完成，原因尚未分类", Action: "检查来源与存储后重试；持续失败时报告任务编号"},
	FailureSourceMissing:           {Message: "导入来源不存在", Action: "恢复来源及全部所需文件后重试"},
	FailureSourcePermission:        {Message: "无法访问导入来源", Action: "检查来源读取权限后重试"},
	FailureSourceRead:              {Message: "读取导入来源失败", Action: "检查来源是否可读、是否正在使用后重试"},
	FailureSourceUnsafe:            {Message: "导入来源含不允许的文件或路径", Action: "移除符号链接、特殊文件或不安全路径后重试"},
	FailureSourceChanged:           {Message: "导入期间来源发生变化", Action: "停止修改来源并确认完整后重试"},
	FailureCrossDevice:             {Message: "导入根与媒体根不在同一文件系统", Action: "将导入根和媒体根挂载在同一卷后重试；导入只搬移目录，不复制媒体"},
	FailureNoMedia:                 {Message: "来源中没有支持的媒体", Action: "确认目录或归档包含支持的图片或视频后重试"},
	FailureLimit:                   {Message: "来源超过导入限制", Action: "减少媒体数量、目录深度或文件体积后重试"},
	FailureImage:                   {Message: "图片格式或尺寸无效", Action: "修复或移除损坏图片后重试"},
	FailureArchive:                 {Message: "归档格式、校验或内容无效", Action: "重新生成或获取完整的 ZIP/CBZ 后重试"},
	FailureVolumeMissing:           {Message: "归档缺少分卷", Action: "将全部分卷放回同一来源目录后重试"},
	FailureMetadata:                {Message: "ComicInfo 资料无效", Action: "检查重复文件、XML 内容和字段长度后重试"},
	FailureVideoUnavailable:        {Message: "视频探测工具不可用", Action: "检查部署中的媒体工具及执行权限后重试"},
	FailureVideoInvalid:            {Message: "文件中没有有效视频", Action: "确认容器和视频流有效后重试"},
	FailureVideoProbe:              {Message: "视频探测失败", Action: "检查视频完整性与工具支持范围后重试"},
	FailureVideoTimeout:            {Message: "视频探测超时", Action: "检查视频复杂度与主机负载后重试"},
	FailureWrite:                   {Message: "媒体存储操作失败", Action: "检查剩余空间、写入权限和存储设备后重试"},
	FailurePublishConflict:         {Message: "发布目标已存在", Action: "检查发布目录和恢复记录，勿直接删除原件后盲目重试"},
	FailurePublishedChanged:        {Message: "暂存或已发布媒体与清单不一致", Action: "检查缺失或变动的文件，恢复原件后重试"},
	FailurePublishedRead:           {Message: "无法校验暂存或已发布媒体", Action: "检查文件读取权限和存储状态后重试"},
	FailureManifest:                {Message: "发布恢复清单无效", Action: "保留现场并检查任务记录，避免盲目重试或删除文件"},
	FailureInternal:                {Message: "任务处理器异常", Action: "报告任务编号，修复程序后重试"},
	FailureCommit:                  {Message: "任务结果未能提交", Action: "检查数据库和存储状态后重试"},
	FailureInterrupted:             {Message: "任务执行意外中断", Action: "检查运行状态后重试"},
}

func failureCode(code string) string {
	if _, ok := failures[code]; !ok {
		return FailureUnknown
	}
	return code
}

func CodeOf(err error) string {
	var coded *failureError
	if errors.As(err, &coded) {
		return failureCode(coded.code)
	}
	return FailureUnknown
}

func WithDiagnostics(task Task) Task {
	task.CanCancel, task.CanRetry = CanCancel(task), CanRetryTask(task)
	task.CancellationReason = CancellationReason(task)
	if task.Status == TaskStatusFailed {
		code := FailureUnknown
		if task.ErrorCode != nil {
			code = failureCode(*task.ErrorCode)
		}
		description := failures[code]
		task.ErrorCode, task.Failure = &code, &description
	} else {
		task.ErrorCode, task.Failure = nil, nil
	}
	return task
}

// FinishState 在持久状态快照上决定终态，已受理的用户取消优先于执行失败。
func FinishState(current, requested, code string) (string, *string, error) {
	if current != TaskStatusRunning && current != TaskStatusCancelling {
		return "", nil, ErrConflict
	}
	if current == TaskStatusCancelling {
		return TaskStatusCancelled, nil, nil
	}
	if requested != TaskStatusFailed {
		return "", nil, ErrConflict
	}
	code = failureCode(code)
	return TaskStatusFailed, &code, nil
}

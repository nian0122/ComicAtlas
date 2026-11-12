package domain

import "errors"

var ErrValidation = errors.New("AI 分析输入无效或超出预算")
var ErrProtocol = errors.New("AI 服务响应无效")
var ErrUnavailable = errors.New("AI 服务不可用")
var ErrBusy = errors.New("AI 服务繁忙")
var ErrSource = errors.New("AI 抽样图片与冻结输入不一致")
var ErrNotFound = errors.New("AI 分析漫画不存在或不可见")
var ErrPreparation = errors.New("AI 抽样图片准备失败")

"""公开错误只使用固定摘要，不带路径或模型响应。"""


class InvalidInput(Exception):
    pass


class InvalidCandidate(Exception):
    pass


class ProviderUnavailable(Exception):
    pass


class ProviderBusy(Exception):
    pass

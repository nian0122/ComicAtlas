UPDATE storage_observations SET result_json = json_remove(result_json, '$.temp');

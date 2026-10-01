output "table_name" {
  value = aws_dynamodb_table.inventory.name
}

output "repository_url" {
  value = aws_ecr_repository.api.repository_url
}

output "api_url" {
  value = length(aws_apprunner_service.api) == 0 ? null : "https://${aws_apprunner_service.api[0].service_url}"
}

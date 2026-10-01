resource "aws_dynamodb_table" "inventory" {
  name                        = var.project
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "pk"
  range_key                   = "sk"
  deletion_protection_enabled = true
  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "sk"
    type = "S"
  }
  point_in_time_recovery {
    enabled = true
  }
  server_side_encryption {
    enabled = true
  }
}

resource "aws_ecr_repository" "api" {
  name                 = var.project
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_iam_role" "runtime" {
  name = "${var.project}-runtime"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "tasks.apprunner.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "inventory" {
  name = "inventory-only"
  role = aws_iam_role.runtime.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem",
      "dynamodb:DeleteItem", "dynamodb:Query", "dynamodb:ConditionCheckItem", "dynamodb:DescribeTable"]
      Resource = aws_dynamodb_table.inventory.arn
    }]
  })
}

resource "aws_iam_role" "ecr_access" {
  name = "${var.project}-ecr-access"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "build.apprunner.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "ecr_pull" {
  role = aws_iam_role.ecr_access.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability", "ecr:DescribeImages"]
        Resource = aws_ecr_repository.api.arn
      }
    ]
  })
}

resource "aws_apprunner_auto_scaling_configuration_version" "api" {
  auto_scaling_configuration_name = var.project
  max_concurrency                 = 50
  min_size                        = 1
  max_size                        = 2
}

resource "aws_apprunner_service" "api" {
  count                          = var.image_identifier == "" ? 0 : 1
  service_name                   = var.project
  auto_scaling_configuration_arn = aws_apprunner_auto_scaling_configuration_version.api.arn
  source_configuration {
    auto_deployments_enabled = false
    authentication_configuration {
      access_role_arn = aws_iam_role.ecr_access.arn
    }
    image_repository {
      image_identifier      = var.image_identifier
      image_repository_type = "ECR"
      image_configuration {
        port = "8080"
        runtime_environment_variables = {
          AWS_REGION     = var.region
          DYNAMODB_TABLE = aws_dynamodb_table.inventory.name
        }
      }
    }
  }
  instance_configuration {
    cpu               = "1024"
    memory            = "2048"
    instance_role_arn = aws_iam_role.runtime.arn
  }
  health_check_configuration {
    protocol            = "HTTP"
    path                = "/actuator/health/liveness"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 1
    unhealthy_threshold = 5
  }
  depends_on = [aws_iam_role_policy.inventory, aws_iam_role_policy.ecr_pull]
}

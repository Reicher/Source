package main

import (
	"encoding/json"
	"fmt"
	"os"
)

type completion struct {
	Model *string `json:"model"`
	Usage struct {
		PromptTokens     *float64 `json:"prompt_tokens"`
		CompletionTokens *float64 `json:"completion_tokens"`
	} `json:"usage"`
	Timings struct {
		PromptN            *float64 `json:"prompt_n"`
		PredictedN         *float64 `json:"predicted_n"`
		PromptPerSecond    *float64 `json:"prompt_per_second"`
		PredictedPerSecond *float64 `json:"predicted_per_second"`
	} `json:"timings"`
	Choices []struct {
		FinishReason *string `json:"finish_reason"`
		Message      struct {
			Content          string `json:"content"`
			ReasoningContent string `json:"reasoning_content"`
		} `json:"message"`
	} `json:"choices"`
}

type summary struct {
	FinishReason          *string  `json:"finishReason"`
	PromptTokens          *float64 `json:"promptTokens"`
	OutputTokens          *float64 `json:"outputTokens"`
	PromptTokensPerSecond *float64 `json:"promptTokensPerSecond"`
	OutputTokensPerSecond *float64 `json:"outputTokensPerSecond"`
	AnswerBytes           int      `json:"answerBytes"`
	ReasoningPresent      bool     `json:"reasoningPresent"`
	Model                 *string  `json:"model"`
}

func main() {
	var body completion
	if err := json.NewDecoder(os.Stdin).Decode(&body); err != nil {
		fmt.Fprintf(os.Stderr, "invalid llama.cpp response: %v\n", err)
		os.Exit(1)
	}
	result := summary{PromptTokens: first(body.Usage.PromptTokens, body.Timings.PromptN), OutputTokens: first(body.Usage.CompletionTokens, body.Timings.PredictedN), PromptTokensPerSecond: body.Timings.PromptPerSecond, OutputTokensPerSecond: body.Timings.PredictedPerSecond, Model: body.Model}
	if len(body.Choices) > 0 {
		result.FinishReason = body.Choices[0].FinishReason
		result.AnswerBytes = len([]byte(body.Choices[0].Message.Content))
		result.ReasoningPresent = body.Choices[0].Message.ReasoningContent != ""
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(result); err != nil {
		fmt.Fprintf(os.Stderr, "could not write benchmark summary: %v\n", err)
		os.Exit(1)
	}
}

func first(values ...*float64) *float64 {
	for _, value := range values {
		if value != nil {
			return value
		}
	}
	return nil
}

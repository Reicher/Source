package apperror

import "fmt"

type Error struct {
	Status        int
	Code, Message string
	Cause         error
}

func (e *Error) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}
func New(status int, code, message string) *Error {
	return &Error{Status: status, Code: code, Message: message}
}
func Wrap(status int, code, message string, cause error) *Error {
	return &Error{Status: status, Code: code, Message: message, Cause: cause}
}
func Details(err error) (int, string, string) {
	if e, ok := err.(*Error); ok {
		return e.Status, e.Code, e.Message
	}
	return 500, "internal_error", err.Error()
}

import { render, screen } from '@testing-library/react'
import { App } from './App'

test('renders the foundation status', () => {
  render(<App />)
  expect(screen.getByText('Foundation ready')).toBeInTheDocument()
})

